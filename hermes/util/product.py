#########################################################################
# Copyright 2018 - 2019 VMware, Inc.  All rights reserved. -- VMware Confidential
#########################################################################
import atexit
import collections
import json
import logging
import os
import os.path
import pathlib
from rest.request import Request
from rpc.rpc_call import RPC
import shutil
import socket
import subprocess
from threading import Thread
import time
import yaml
import signal
from util import helper

PRODUCT_LOGS_DIR = "product_logs"
log = logging.getLogger(__name__)

class ConcordInstsanceMetaData():
   _processIndex = None
   _processCmd = None
   _logFile = None
   _instanceId = None
   _path = None
   _containerName = None

   def __init__(self, pIndex, pCmd, logFile, id, path):
      self._processIndex = pIndex
      self._processCmd = pCmd
      self._logFile = logFile
      self._instanceId = id
      self._path = path

class Product():
   '''
   Represents an instance of the product.  That includes all of the processes
   needed to have "the product" running.
   '''
   _atexitSetup = False # Whether atexit has been set up.
   _ethrpcApiUrl = None
   _logs = []
   _processes = []
   _concordProcessesMetaData = []
   _cmdlineArgs = None
   userProductConfig = None
   _servicesToLogLater = ["db-server-init1", "db-server-init2", "fluentd"]
   _numProductStarts = 0
   docker_env = helper.get_docker_env()

   PERSEPHONE_SERVICE_METADATA = docker_env["persephone_metadata_repo"]
   PERSEPHONE_SERVICE_PROVISIONING = docker_env["persephone_provisioning_repo"]
   # PERSEPHONE_SERVICE_FLEET = docker_env["persephone_fleet_repo"]

   def __init__(self, cmdlineArgs, userConfig):
      self._cmdlineArgs = cmdlineArgs
      self._userConfig = userConfig
      self.userProductConfig = userConfig["product"]
      self._productLogsDir = os.path.join(self._cmdlineArgs.resultsDir, PRODUCT_LOGS_DIR)
      pathlib.Path(self._productLogsDir).mkdir(parents=True, exist_ok=True)
      self.concordNodesDeployed = []


   def launchProduct(self):
      '''
      Given the user's product config section, launch the product.
      Raises an exception if it cannot start.
      '''
      Product._numProductStarts += 1

      if not Product._atexitSetup:
         atexit.register(self.stopProduct)
         Product._atexitSetup = True

      # Workaround for intermittent product launch issues.
      numAttempts = 0
      launched = False

      while (not launched) and (numAttempts < self._cmdlineArgs.productLaunchAttempts):
         try:
            self._launchViaDocker()
            launched = True

         except Exception as e:
            numAttempts += 1
            log.info("Attempt {} to launch the product failed. Exception: '{}'".format(
               numAttempts, str(e)))

            if numAttempts < self._cmdlineArgs.productLaunchAttempts:
               log.info("Stopping whatever was launched and attempting to launch again.")
               self.stopProduct()

      if not launched:
         raise Exception("Failed to launch the product after {} attempt(s). Exiting".format(numAttempts))


   def launchPersephone(self):
      '''
      Given the user's product config section, launch the product.
      Raises an exception if it cannot start.
      '''
      atexit.register(self.stopPersephone)
      launched = False

      try:
         self._launchPersephone()
         launched = True
      except Exception as e:
         log.error("Attempt to launch the product failed. Exception: '{}'"
                   .format(str(e)))

         log.info("Stopping whatever was launched")
         self.stopPersephone()

      if not launched:
         raise Exception("Failed to launch the product. Exiting")


   def validatePaths(self, paths):
      '''Make sure the given paths are all valid.'''
      for path in paths:
         if not os.path.isfile(path):
            log.error("The file '{}' does not exist.".format(path))
            return False

      return True


   def mergeDictionaries(self, orig, new):
      '''Python's update() simply replaces keys at the top level.'''
      for newK, newV in new.items():
         if newK in orig:
            if isinstance(newV, collections.Mapping):
               self.mergeDictionaries(orig[newK], newV)
            elif isinstance(newV, list):
               orig[newK] = orig[newK] + newV
            else:
               orig[newK] = newV
         else:
            orig[newK] = newV

   def _isHelenInDockerCompose(self, dockerCfg):
      for service in dockerCfg['services']:
          if "helen" in service:
              return True
      return False

   def _launchViaDocker(self):
      dockerCfg = {}

      if self.validatePaths(self._cmdlineArgs.dockerComposeFile):
         for cfgFile in self._cmdlineArgs.dockerComposeFile:
            with open(cfgFile, "r") as f:
               newCfg = yaml.load(f)
               self.mergeDictionaries(dockerCfg, newCfg)

         helper.copy_docker_env_file()

         if (self._cmdlineArgs.runConcordConfigurationGeneration):
            self._generateConcordConfiguration(
               self._cmdlineArgs.concordConfigurationInput)

         if not self._cmdlineArgs.keepconcordDB:
            self.clearDBsForDockerLaunch(dockerCfg)
            if self._isHelenInDockerCompose(dockerCfg):
               self.initializeHelenDockerDB(dockerCfg)

            # DB init is handled by docker-compose now; I think this can be removed.
            # Do it in a separate checkin.
            self.initializeHelenDockerDB(dockerCfg)

         self._startContainers()
         self._startLogCollection()

         if not self._waitForProductStartup():
            raise Exception("The product did not start.")

         self.concordNodesDeployed = self._getConcordNodes(dockerCfg)
      else:
         raise Exception("The docker compose file list contains an invalid value.")

   def _generateConcordConfiguration(self, concordCfg):
       '''
       Runs Concord configuration generation for the test Concord cluster that
       will be launched with the product and moves the generated configuration
       files to where they are needed. This function runs configuration
       generation in Concord's Docker container and uses a script for moving the
       configuration files. This function uses the .env file to configure which
       Concord image to use for configuration generation, and it gets
       information on which volume to run configuration generation in and what
       script to use to move the generated files from the Hermes user config.
       This function may throw an exception in the event of any failures during
       configuraiton generation.
       '''

       # It is necessary to convert the path for the directory to run
       # configuration generation in to an absolute path (if it is not already)
       # as docker run will refuse to bind-mount host directories given by
       # relative paths.
       assert (os.path.basename(os.getcwd()) == "hermes")

       dockerEnv = self.docker_env

       assert self._userConfig is not None, "User config is missing."
       try:
           concordRepo = dockerEnv["concord_repo"]
           concordTag = dockerEnv["concord_tag"]
       except KeyError as key:
           raise Exception ('Docker .env field "{}" is missing.'.format(key))
       try:
           concordConfigConfig = \
               self._userConfig["concordConfigurationGeneration"]
           configVolumePath = concordConfigConfig["configVolumePath"];
           configDistributionScriptCommand = \
               [concordConfigConfig["configDistributionScript"]];
           configDistributionScriptCommand += \
               concordConfigConfig["configDistributionArgs"];
       except KeyError as key:
           raise Exception ('User config field "{}" is missing.'.format(key)) 
       print(os.getcwd())
       print(os.path.basename(os.getcwd()))
       if not os.path.isabs(configVolumePath):
           configVolumePath = os.path.join(os.getcwd(), configVolumePath)

       runCommand = ["docker", "run", "--mount"]
       runCommand += ["type=bind,source=" + configVolumePath + \
                      ",destination=/concord/config"]
       imageName = concordRepo + ":" + concordTag
       runCommand += [imageName]
       runCommand += ["/concord/conc_genconfig"]
       runCommand += ["--configuration-input", concordCfg]
       runCommand += ["--output-name", "/concord/config/concord"]

       completedProcess = subprocess.run(runCommand, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT)

       completedProcess.check_returncode()

       completedProcess = subprocess.run(configDistributionScriptCommand,
           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

       completedProcess.check_returncode()
       log.info("Successfully generated configuration for Concord cluster," \
           " including fresh cryptographic keys.")

   def _launchPersephone(self):
      dockerCfg = {}

      if self.validatePaths(self._cmdlineArgs.dockerComposeFile):
         for cfgFile in self._cmdlineArgs.dockerComposeFile:
            with open(cfgFile, "r") as f:
               newCfg = yaml.load(f)
               self.mergeDictionaries(dockerCfg, newCfg)

         helper.copy_docker_env_file()
         self._startContainers(product="persephone")
         self._startLogCollection()

         for dockerComposeFile in self._cmdlineArgs.dockerComposeFile:
            with open(dockerComposeFile, "r") as yamlFile:
               composeData = yaml.load(yamlFile)

            for service in list(composeData["services"]):
               if not self._waitForPersephoneStartup(service):
                  raise Exception("The product did not start.")

      else:
         raise Exception("The docker compose file list contains an invalid value.")


   def _startContainers(self, product="concord"):
      cmd = ["docker-compose"]

      for cfgFile in self._cmdlineArgs.dockerComposeFile:
         cmd += ["--file", cfgFile]

      cmd += ["up"]
      log.debug("Launching via docker-compose with {}".format(cmd))

      # We capture output in individual services' logs now, but still capture
      # all output just in case.
      bigLog = self._openLog("{}_".format(product) + str(Product._numProductStarts))
      p = subprocess.Popen(cmd,
                           stdout=bigLog,
                           stderr=subprocess.STDOUT)
      self._processes.append(p)


   def _startLogCollection(self):
      '''
      Launches processes to save the output of services listed in the docker-compose
      files.
      '''
      for dockerComposeFile in self._cmdlineArgs.dockerComposeFile:
         composeData = None
         logLaunchThreads = []

         with open(dockerComposeFile, "r") as yamlFile:
            composeData = yaml.load(yamlFile)

         for service in list(composeData["services"]):
            if service in self._servicesToLogLater.copy():
               self._servicesToLogLater.remove(service)
               self._servicesToLogLater.append((service,os.path.abspath(dockerComposeFile)))
            else:
               logLaunchThread = Thread(target=self._startLogLaunchThread,
                                        args=(service,os.path.abspath(dockerComposeFile)))
               logLaunchThread.start()
               logLaunchThreads.append(logLaunchThread)

         for t in logLaunchThreads:
            t.join()


   def _startLogLaunchThread(self, service, dockerComposeFile):
      '''
      We have to wait until a docker container has started before we can use
      "docker-compose log" to track it.
      We also don't know (and don't want to have to know) the order in which
      the docker services start, and we don't want to miss logging of something
      which starts early because we're waiting for something which starts late.
      So, we will launch threads.  Each thread will wait for its service
      to start, then create a docker-compose subprocess to save its logs
      Returns the thread.
      '''
      self._waitForContainerToStart(service, dockerComposeFile)
      logFile = self._openLog(service + "_" + str(Product._numProductStarts))
      log.info("Service {} log file: {}".format(service, logFile.name))
      cmd = ["docker-compose", "-f", dockerComposeFile, "logs", "-f", service]
      p = subprocess.Popen(cmd,
                           stdout=logFile,
                           stderr=subprocess.STDOUT)
      self._processes.append(p)


   def _waitForContainerToStart(self, service, dockerComposeFile):
      '''
      Given a service name, waits for its container to start, using "docker-compose top".
      Note that this is just whether the service *starts*, not whether the
      product starts successfully.
      At this time, the "docker-compose up" process has already been launched.
      If this polling does not detect a service starting, then either:
      - The service ran too quickly (e.g. the DB setup services) during the sleep time.
        The services known to do that are in _servicesToLogLater.
      - The service failed to start.
      In both cases, the service's name will be stored and its log generated at the end
      of the run.
      '''
      sleepTime = 5
      maxTries = 24 # 2 min.  Services which we don't catch will be addressed later.
      numTries = 0

      while numTries < maxTries:
         numTries += 1
         topCmd = ["docker-compose", "-f", dockerComposeFile, "top", service]
         completedProcess = subprocess.run(topCmd,
                                           stdout=subprocess.PIPE,
                                           stderr=subprocess.STDOUT)
         psOutput = completedProcess.stdout.decode("UTF-8")

         # If a service named "concord1" is running, the first line of the output is
         # is "docker_concord1_1", followed by a table.  If it is not running, then
         # that line and the table are not present.
         if service in psOutput:
            return
         else:
            log.debug("Waiting for the {} container to start.".format(service))
            time.sleep(sleepTime)

      msg = "Timed out waiting for the '{}' service to start so a logging process " \
            "could be assigned to it.  Any logs it produced will be generated at " \
            "the end of the test run.".format(service)
      self._servicesToLogLater.append((service,dockerComposeFile))
      log.info(msg)


   def _openLog(self, service):
      '''
      Created a function for this because:
      1. The log files should be add to self._logs so they are tidily closed
         when we finish the test run.
      2. The log files should be placed in the test run's directory.

      Accepts a service name and returns the open file object.
      '''
      logFile = open(self._createLogPath(service), "w")
      self._logs.append(logFile)
      return logFile


   def _createLogPath(self, service):
      return os.path.join(self._productLogsDir, service + ".log")


   def _getConcordNodes(self, cfg):
      '''
      Returns the concord services in the docker-compose config.
      '''
      concordNodes = []

      if "services" in cfg:
         for service in cfg["services"]:
            if service.startswith("concord"):
               concordNodes.append(service)

      return concordNodes


   def getUrlFromEthrpcNode(self, node):
      return node["rpc_url"]


   def getEthrpcNodes(self):
      members = []
      request = Request(self._productLogsDir,
                        "getMembers",
                        self._cmdlineArgs.reverseProxyApiBaseUrl,
                        self._userConfig)
      blockchains = request.getBlockchains()
      result = request.getMemberList(blockchains[0]["id"])

      for m in result:
         if m["rpc_url"]:
            members.append(m)

      log.info("Ethrpc members reported by Helen:")
      if members:
         for m in members:
           log.info("  {}: {}".format(m["hostname"],m["rpc_url"]))
      else:
         log.info("  None were found.")

      return members


   def clearconcordDBForCmdlineLaunch(self, concordSection, serviceName=None):
      '''
      Deletes the concord DB so we get a clean start.
      Other test suites can leave it in a state that makes
      it fail.
      Note that Helen's DB is cleared by the cockroach shell script
      when the product is launched via command line.
      '''
      params = None

      log.debug("serviceName{}".format(serviceName))

      for subSection in concordSection:
         if serviceName is None:
             if subSection.lower().startswith("concord"):
                 params = concordSection[subSection]["parameters"]
         elif subSection.lower() == serviceName:
             params = concordSection[subSection]["parameters"]

      isConfigParam = False
      buildRoot = None

      for param in params:
         if isConfigParam:
            configFile = os.path.join(concordSection["buildRoot"],
                                      param)
            configFile = os.path.expanduser(configFile)
            subPath = None

            with open (configFile, "r") as props:
               for prop in props:
                  prop = prop.strip()
                  if prop and not prop.startswith("#") and \
                     prop.lower().startswith("blockchain_db_path"):
                     subPath = prop.split("=")[1]

            buildRoot = os.path.abspath(concordSection["buildRoot"])
            dbPath = os.path.join(concordSection["buildRoot"], subPath)
            dbPath = os.path.expanduser(dbPath)
            if os.path.isdir(dbPath):
               log.debug("Clearing concord DB directory '{}'".format(dbPath))
               shutil.rmtree(dbPath)
            isConfigParam = False

         if param == "-c":
            isConfigParam = True

      return buildRoot


   def pullHelenDBImage(self, dockerCfg):
      '''This is the cockroach DB.  Make sure we have it before trying to start
         the product so we don't time out while downloading it.'''
      image = dockerCfg["services"]["db-server"]["image"]
      image_name = image.split(":")[0]
      pull_cmd = ["docker", "pull", image]
      find_cmd = ["docker", "images", "--filter", "reference="+image]
      found = False

      completedProcess = subprocess.run(pull_cmd,
                                        stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT)
      # Sleep just in case there is a gap between the time "pull" finishes
      # and "images" can find it. In testing, it looks immediate.
      time.sleep(1)
      completedProcess = subprocess.run(find_cmd,
                                        stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT)
      psOutput = completedProcess.stdout.decode("UTF-8")

      if image_name in psOutput:
         found = True

      return found


   def startHelenDockerDB(self, dockerCfg):
      ''' Starts the Helen DB.  Returns True if able to start, False if not.'''
      cmd = ["docker-compose"]

      for cfgFile in self._cmdlineArgs.dockerComposeFile:
         cmd += ["--file", cfgFile]

      cmd += ["up", "db-server"]
      log.debug("Launching Helen DB with command '{}'".format(cmd))
      subprocess.Popen(cmd,
                       stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT)
      sleepTime = 3
      maxTries = 10
      numTries = 0
      dbRunning = False
      dbPort = int(dockerCfg["services"]["db-server"]["ports"][0].split(":")[0])

      while numTries < maxTries and not dbRunning:
         sock = socket.socket()
         log.debug("Attempting to connect to the Helen DB server on port {}.".format(dbPort))

         try:
            sock.connect(("localhost", dbPort)) # Product may have a remote DB someday.
         except Exception as e:
            log.debug("Waiting for the Helen DB server: '{}'".format(e))

            if numTries < maxTries:
               numTries += 1
               log.debug("Will try again in {} seconds.".format(sleepTime))
               time.sleep(sleepTime)
         else:
            log.debug("Helen DB is up.")
            dbRunning = True
         finally:
            sock.close()

      return dbRunning


   def getRunningContainerIds(self, searchString):
      '''
      Return the docker container Id(s) which are running and whose "docker ps" output
      contains the given search string.
      '''
      containerIds = []

      # The processes cmd gives us a string like:
      # CONTAINER ID        IMAGE                          COMMAND ...
      # 21d37f282847        cockroachdb/cockroach:v2.0.2   "/cockroach/cockroac…" ...
      cmd = ["docker", "ps", "--filter", "status=running"]
      log.debug("Getting running containers with command '{}'".format(cmd))
      completedProcess = subprocess.run(cmd,
                                        stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT)
      psOutput = completedProcess.stdout.decode("UTF-8")
      lines = psOutput.split("\n")

      for line in lines:
         if searchString in line:
            log.debug("Found container '{}' with search string '{}' in the ps output.".format(line, searchString))
            fields = line.strip().split(" ")
            containerIds.append(fields[0])

      if not containerIds:
         log.debug("Unable to find a running container for '{}'.".format(searchString))
         log.debug("stdout: '{}', stderr: '{}'".format(completedProcess.stdout, completedProcess.stderr))

      return containerIds


   def getHelenDBContainerId(self, dockerCfg):
      '''Returns the running Helen DB container's ID, or None if it cannot be found.'''
      dbImageName = dockerCfg["services"]["db-server"]["image"]
      containerId = None
      sleepTime = 3
      maxTries = 10
      numTries = 0

      while numTries < maxTries and not containerId:
         containerIds = self.getRunningContainerIds(dbImageName)

         if containerIds:
            containerId = containerIds[0]
            if numTries < maxTries:
               numTries += 1
               log.debug("Will try again in {} seconds.".format(sleepTime))
               time.sleep(sleepTime)

      return containerId


   def configureHelenDockerDB(self, containerId):
      '''Runs the SQL commands to set up the Helen DB.  Returns whether the command
         exit codes indicate success.
      '''
      schema = None

      with open("../helen/src/main/resources/database/schema.sql", "r") as f:
         schema = f.read()

      commands = [
         ["docker", "exec", containerId, "./cockroach", "user", "set", "helen_admin", "--insecure"],
         ["docker", "exec", containerId, "./cockroach", "sql", "--insecure", "-e", schema],
      ]

      for cmd in commands:
         log.info("running '{}'".format(cmd))
         completedProcess = subprocess.run(cmd,
                                           stdout=subprocess.PIPE,
                                           stderr=subprocess.STDOUT)
         try:
            completedProcess.check_returncode()
            log.info("stdout: {}, stderr: {}".format(completedProcess.stdout, completedProcess.stderr))
         except subprocess.CalledProcessError as e:
            log.error("Command '{}' to configure the Helen DB failed.  Exit code: '{}'".format(cmd, e.returncode))
            log.error("stdout: '{}', stderr: '{}'".format(completedProcess.stdout, completedProcess.stderr))
            return False

      return True


   def stopDockerContainer(self, containerId):
      '''Stops the given docker container. Returns whether the exit code indicated success.'''
      log.info("Stopping '{}'".format(containerId))
      cmd = ["docker", "kill", containerId]
      completedProcess = subprocess.run(cmd,
                                        stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT)
      try:
         completedProcess.check_returncode()
         if containerId:
            self.removeDockerContainer(containerId)
      except subprocess.CalledProcessError as e:
         log.error("Command '{}' to stop container '{}' failed.  Exit code: '{}'".format(cmd,
                                                                                         containerId,
                                                                                         e.returncode))
         log.error("stdout: '{}', stderr: '{}'".format(completedProcess.stdout, completedProcess.stderr))
         return False

      return True


   def removeDockerContainer(self, containerId):
      '''Remove the given docker container. Returns whether the exit code indicated success.'''
      log.info("Removing '{}'".format(containerId))
      cmd = ["docker", "rm", containerId]
      completedProcess = subprocess.run(cmd,
                                        stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT)
      try:
         completedProcess.check_returncode()
      except subprocess.CalledProcessError as e:
         log.error("Command '{}' to remove container '{}' failed.  Exit code: '{}'".format(cmd,
                                                                                         containerId,
                                                                                         e.returncode))
         log.error("stdout: '{}', stderr: '{}'".format(completedProcess.stdout, completedProcess.stderr))
         return False

      return True


   def initializeHelenDockerDB(self, dockerCfg):
      '''When the product as Docker containers, we need to initialize the Helen DB
         in a different way than when launched via command line.  Raises an exception
         on error.'''
      if not self.pullHelenDBImage(dockerCfg):
         raise Exception("Unable to pull the Helen DB image.")

      if not self.startHelenDockerDB(dockerCfg):
         raise Exception("The Helen DB failed to come up.")

      containerId = self.getHelenDBContainerId(dockerCfg)
      if not containerId:
         raise Exception("Unable to get the running Helen DB's docker container ID.")

      if not self.configureHelenDockerDB(containerId):
         raise Exception("Unable to configure the Helen DB.")

      if not self.stopDockerContainer(containerId):
         raise Exception("Failure trying to stop the Helen DB.")


   def clearDBsForDockerLaunch(self, dockerCfg, serviceName=None):
      concordDbPath = None
      for service in dockerCfg["services"]:
         if serviceName is None or  service == serviceName:
             serviceObj = dockerCfg["services"][service]
             if "volumes" in serviceObj:
                for v in serviceObj["volumes"]:
                   if "rocksdbdata" in v or \
                      "cockroachDB" in v:
                      yamlDir = os.path.dirname(self._cmdlineArgs.dockerComposeFile[0])
                      deleteMe = os.path.join(yamlDir, v.split(":")[0])
                      log.info("Deleting: {}".format(deleteMe))
                      if serviceName == service and "rocksdbdata" in v:
                        concordDbPath = os.path.abspath(yamlDir)
                      if os.path.isdir(deleteMe):
                         try:
                            shutil.rmtree(deleteMe)
                         except PermissionError as e:
                            log.error("Could not delete {}. Try running with sudo " \
                                      "when running in docker mode.".format(deleteMe))
                            raise e
      return concordDbPath


   def stopProcessesInContainers(self, containerSearchString, processSearchString):
      '''
      containerSearchString: A string appearing in the "docker ps" output for the container
      to look for.
      processSearchSring: A string appearing in the docker container's "ps" output for
      the process to look for.
      Sends a process (processSearchString) in a container (containerSearchString)
      a polite request to stop before we kill the container in which it is running.
      This is needed because a "docker kill" of a container abruptly terminates a utility
      such as Valgrind, which prevents it from summarizing memory leak data.
      '''
      containerIds = self.getRunningContainerIds(containerSearchString)

      for containerId in containerIds:
         cmd = ["docker", "exec", containerId, "ps", "-x"]
         completedProcess = subprocess.run(cmd,
                                        stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT)
         psOutput = completedProcess.stdout.decode("UTF-8")
         lines = psOutput.split("\n")

         for line in lines:
            if processSearchString in line and not "/bin/sh" in line:
               fields = line.strip().split(" ")
               processId = fields[0]
               log.info("Killing process id '{}' in container '{}' because we were " \
                        "asked to kill processes containing '{}' in containers " \
                        "containing '{}'".format(processId, containerId,
                                               processSearchString, containerSearchString))
               cmd = ["docker", "exec", containerId, "kill", processId]
               completedProcess = subprocess.run(cmd,
                                              stdout=subprocess.PIPE,
                                              stderr=subprocess.STDOUT)
               psOutput = completedProcess.stdout.decode("UTF-8")
               log.info("Kill command output: {}".format(psOutput))


   def stopMemoryLeakNode(self):
      '''
      We need to send Valgrind a polite request to terminate before
      killing the container it is running in so that it can summarize
      memory leak information.  Then we give it a few seconds to do so.
      '''
      self.stopProcessesInContainers("memleak", "valgrind")
      time.sleep(10)


   def _logServicesAtEnd(self):
      '''
      Run "docker-compose logs" for any services flagged for late log
      gathering.
      '''
      for service in self._servicesToLogLater:
         if isinstance(service, tuple):
            serviceName = service[0]
            dockerComposeFile = service[1]
            logFileName = self._createLogPath(serviceName)

            if not os.path.isfile(logFileName):
               logFile = self._openLog(serviceName + "_" + str(Product._numProductStarts))
               log.info("Service {} log file: {}".format(serviceName, logFile.name))
               cmd = ["docker-compose", "-f", dockerComposeFile, "logs", serviceName]
               subprocess.run(cmd,
                              stdout=logFile,
                              stderr=subprocess.STDOUT)


   def stopProduct(self):
      '''
      Stops the product executables, closes the logs, and generates logs for
      services which may have run too quickly or failed to start.
      '''
      self._logServicesAtEnd()

      self.stopMemoryLeakNode()
      cmd = ["docker-compose"]

      for cfgFile in self._cmdlineArgs.dockerComposeFile:
         cmd += ["--file", cfgFile]

      cmd += ["down"]
      log.info("Stopping the product with command '{}'".format(cmd))
      p = subprocess.run(cmd)

      for l in self._logs[:]:
         log.info("Closing log: {}".format(l.name))
         l.close()
         self._logs.remove(l)


   def stopPersephone(self):
      '''
      Stops the product executables and closes the logs.
      '''
      container_ids = self.getRunningContainerIds("persephone")
      log.info("Container IDs found: {0}".format(container_ids))

      for container_id in container_ids:
         if container_id:
            log.info("Terminating container ID: {0}".format(container_id))
            if not self.stopDockerContainer(container_id):
               raise Exception("Failure trying to stop docker container.")

      for l in self._logs[:]:
         l.close()
         self._logs.remove(l)


   def _waitForProductStartup(self):
      '''
      Waits for Helen to be up, then waits for Concord to be up.
      '''
      retries = 20
      attempts = 0
      sleepTime = 15
      startupLogDir = os.path.join(self._cmdlineArgs.resultsDir, PRODUCT_LOGS_DIR,
                                   "waitForStartup")
      nodes = None

      while attempts < retries:
         try:
            nodes = self.getEthrpcNodes()
            if self.nodesReady(nodes):
               break
            else:
               raise Exception("Still waiting for Concord nodes to be ready.")
         except Exception as e:
            attempts += 1
            log.info("Caught an exception, probably because Helen is still starting up: {}".format(e))

            if attempts <= retries:
               time.sleep(sleepTime)
               log.info("Waiting for Helen to become responsive...")
            else:
               log.error("Helen never returned ethrpc nodes.")
               return False

      if self._cmdlineArgs.ethrpcApiUrl:
         self._ethrpcApiUrl = self._cmdlineArgs.ethrpcApiUrl
      else:
         self._ethrpcApiUrl = self.getUrlFromEthrpcNode(nodes[0])

      rpc = RPC(startupLogDir,
                "addApiUser",
                self._ethrpcApiUrl,
                self._userConfig)

      log.info("Adding an API user via Helen...")
      rpc.addUser(self._cmdlineArgs.reverseProxyApiBaseUrl)
      return True


   def _waitForPersephoneStartup(self, service):
      '''
      Check if all microservices of persephone are up
      '''
      retries = 5
      attempts = 0
      sleepTime = 3
      log_filename = "{}.log".format(
         service + "_" + str(Product._numProductStarts))
      log_file = os.path.join(self._productLogsDir, log_filename)
      while attempts < retries:
         with open(log_file) as f:
            persephone_log = f.read()
            log.debug(persephone_log)
            if "Service instance initialized" in persephone_log:
               log.info("Microservice '{}' started successfully!".format(service))
               return True
            if "port is already allocated" in persephone_log:
               log.error("\n**** persephone server is already running. "
                         "Check logs for more info")
               return False

         attempts += 1
         log.info(
            "Waiting for {} seconds to check again if persephone micro-services are up...".format(
               sleepTime))
         time.sleep(sleepTime)

      return False


   def nodesReady(self, nodes):
      '''
      Returns whether all of the nodes report being ready.
      Yes, the framework is using the thing it is testing, but we're not going
      to ssh into the Concord nodes or something like that.  If the status
      goes to live when nodes aren't live, we'll know when the first test case
      runs.
      '''
      if nodes and len(nodes) > 0:
         allReady = True

         for node in nodes:
            allReady = allReady and node["status"] == "live"

         return allReady
      else:
         return False


   def cleanConcordDb(self, instanceId):
      if len(self._concordProcessesMetaData) == 0:
         if os.path.isfile(self._cmdlineArgs.dockerComposeFile[0]):
            with open(self._cmdlineArgs.dockerComposeFile[0], "r") as f:
               dockerCfg = yaml.load(f)
               res= self.clearDBsForDockerLaunch(dockerCfg, "concord{}".format(instanceId))
               return res
      else:
        for launchElement in self.userProductConfig["launch"]:
           for project in launchElement:
              projectSection = launchElement[project]
              buildRoot = projectSection["buildRoot"]
              buildRoot = os.path.expanduser(buildRoot)

              if not self._cmdlineArgs.keepconcordDB and \
                 project.lower() == "concord" + str(instanceId):
                 path = self.clearconcordDBForCmdlineLaunch(launchElement[project], "concord" + str(instanceId))
                 return path

   def get_concord_container_name(self, replicaId):
      command = 'docker ps --format "{0}" | grep concord{1}'.format("{{ .Names }}", replicaId)
      output = subprocess.Popen(command,stderr=subprocess.PIPE, shell=True, stdout=subprocess.PIPE).stdout.read().decode().replace(os.linesep,"")
      return output

   def action_on_concord_container(self, containerName, action):
      command = "docker {0} {1}".format(action, containerName)
      output = subprocess.Popen(command,stderr=subprocess.PIPE, shell=True, stdout=subprocess.PIPE).stdout.read().decode().replace(os.linesep,"")
      if output != containerName:
        return False
      return True

   def start_concord_replica(self, id):
       if len(self._concordProcessesMetaData) == 0:
          containerName = "docker_concord{}_1".format(id)
          return self.action_on_concord_container(containerName, "start")

       originalCwd = os.getcwd()
       result = False
       for idx, meta in enumerate(self._concordProcessesMetaData):
           if meta._instanceId == id:
               log.info("Starting concord replica{}".format(id))
               os.chdir(meta._path)
               p = subprocess.Popen(meta._processCmd,
                                    stdout=meta._logFile,
                                    stderr=subprocess.STDOUT)
               log.info("Concord replica{} started".format(id))
               self._processes.append(p)
               meta._processIndex = len(self._processes) - 1
               self._concordProcessesMetaData[idx] = meta
               result = True
               break
       os.chdir(originalCwd)
       return result

   def kill_concord_replica(self, id):
       if len(self._concordProcessesMetaData) == 0:
          containerName = self.get_concord_container_name(id)
          if len(containerName) == 0:
             return False
          return self.action_on_concord_container(containerName, "kill")

       for meta in self._concordProcessesMetaData:
           if meta._instanceId == id:
               log.info("Killing concord replica with ID {}".format(id))
               self._processes[meta._processIndex].kill()
               meta._processIndex = None
               log.info("Killed concord replica with ID {}".format(id))
               return True
       return False

   def pause_concord_replica(self, id):
       if len(self._concordProcessesMetaData) == 0:
          containerName = self.get_concord_container_name(id)
          if len(containerName) == 0:
             return False
          return self.action_on_concord_container(containerName, "pause")

       for meta in self._concordProcessesMetaData:
           if meta._instanceId == id:
               log.info("Suspending concord replica with ID {}".format(id))
               os.kill(self._processes[meta._processIndex].pid, signal.SIGSTOP)
               log.info("Suspended concord replica with ID {}".format(id))
               return True
       return False

   def resume_concord_replica(self,id):
       if len(self._concordProcessesMetaData) == 0:
          containerName = "docker_concord{}_1".format(id)
          return self.action_on_concord_container(containerName, "unpause")

       for meta in self._concordProcessesMetaData:
           if meta._instanceId == id:
               log.info("Resuming concord replica with ID {}".format(id))
               os.kill(self._processes[meta._processIndex].pid, signal.SIGCONT)
               log.info("Resumed concord replica with ID {}".format(id))
       return True