# Copyright 2019 VMware, Inc.  All rights reserved. -- VMware Confidential

# In order to run DAML tests we need a special docker-compose and concord
# configuration file.
#
# main.py --runConcordConfigurationGeneration
#         --concordConfigurationInput /concord/config/dockerConfigurationInput-daml.yaml
#         --dockerComposeFile=../docker/docker-compose-daml.yml
#         DamlTests

import logging
import os
import subprocess
import time
import traceback
from tempfile import NamedTemporaryFile

import util.daml.upload_dar as darutil

from . import test_suite
from util.product import Product

log = logging.getLogger(__name__)

class DamlProduct(Product):
   def _waitForProductStartup(self):
      """Wait for DAML ledger api server to be up and running.

      Note: We depend on the test packages (*.dar files) to be uploaded on
      start up. This will change in the future but for now we have to check
      the server that is uploading the test packages. See docker-compose.
      """
      LOG_READY = "listening on localhost:6865"
      LOG_FILE = self._createLogPath("daml_ledger_api1_1")

      with open(LOG_FILE) as f:
         timeout = 0
         while timeout < 60:
            if any(filter(lambda x: LOG_READY in x, f.readlines())):
               return True
            time.sleep(1)
            timeout += 1

      log.error("DAML ledger api service 1 didn't start in time.")
      return False

class DamlTests(test_suite.TestSuite):

   def __init__(self, passedArgs):
      super(DamlTests, self).__init__(passedArgs)

   def getName(self):
      return "DamlTests"

   def launchProduct(self, cmdlineArgs, userConfig, force=False):
      """Launch the DAML product.
      """
      self.product = DamlProduct(cmdlineArgs, userConfig)

      if force or (self._productMode and not self._noLaunch):
         try:
            self.product.launchProduct()
         except Exception as e:
            log.error(str(e))
            self.writeResult("All Tests", False, "The product did not start.")
            raise

   def run(self):
      """Runs all tests
      """
      try:
         log.info("Launching product...")
         self.launchProduct(self._args,
                            self._userConfig)
      except Exception as e:
         log.error(traceback.format_exc())
         return self._resultFile

      for (testName, testFun) in self._getTests():
         testLogDir = os.path.join(self._testLogDir, testName)
         log.info("Starting test '{}'".format(testName))
         try:
            result, info = testFun()
         except Exception as e:
            result = False
            info = str(e)
            traceback.print_tb(e.__traceback__)
            log.error("Exception running test: '{}'".format(info))

         if info:
            info += "  "
         else:
            info = ""

         relativeLogDir = self.makeRelativeTestPath(testLogDir)
         info += "Log: <a href=\"{}\">{}</a>".format(relativeLogDir,
                                                     testLogDir)
         self.writeResult(testName, result, info)

      log.info("DamlTests are done.")
      return self._resultFile

   def _getTests(self):
      return [("ledger_api_test_tool", self._test_ledger_api_test_tool)]

   def _test_ledger_api_test_tool(self):
      """Run ledger_api_test_tool

      First, copy the test tool, and extract&copy out the DARs.
      """
      TEST_TOOL_NAME = "ledger-api-test-tool_2.12-100.13.16.jar"
      TEST_DARS = ["SemanticTests.dar", "Test.dar"]

      log.info("Copy test tool to ledger api service...")
      with NamedTemporaryFile() as tmp:
         getTool = "docker cp docker_daml_test_tool_1:/" + TEST_TOOL_NAME + " " + tmp.name
         putTool = "docker cp " + tmp.name + " docker_daml_ledger_api1_1:/" + TEST_TOOL_NAME
         try:
            subprocess.check_call(getTool.split())
            subprocess.check_call(putTool.split())
         except subprocess.CalledProcessError as e:
            log.error("Failed to copy test tool: %s", str(e))
            log.error(str(e.output))
            return (False, str(e))

      log.info("Unpack test tool and copy DAR files to hermes...")
      cmd = "docker exec -t docker_daml_ledger_api1_1 " \
            "java -jar /" + TEST_TOOL_NAME + " -x"
      try:
         subprocess.check_call(cmd.split())
      except subprocess.CalledProcessError as e:
         log.error("Failed to unpack test tool: %s", str(e))
         log.error(str(e.output))
         return (False, str(e))

      tmpDars = []
      for testDar in TEST_DARS:
         with NamedTemporaryFile(delete=False) as tmp:
            getDar = "docker cp docker_daml_ledger_api1_1:/doc/daml/" + testDar + " " + tmp.name
            try:
               subprocess.check_call(getDar.split())
            except subprocess.CalledProcessError as e:
               log.error("Failed to copy DAR (%s): %s", testDar, str(e))
               log.error(str(e.output))
               return (False, str(e))
            log.info("Save %s to %s", testDar, tmp.name)
            tmpDars.append(tmp.name)

      log.info("Upload DAR files...")
      for testDar in tmpDars:
         if darutil.upload_dar(host='localhost', port='6861', darfile=testDar):
             os.remove(testDar)
         else:
             msg = "Failed to upload test DAR " + testDar
             log.error(msg)
             return (False, msg)

      # Finally, run the test tool
      cmd = "docker exec -t docker_daml_ledger_api1_1 " \
            "java -jar /" + TEST_TOOL_NAME + " --timeout-scale-factor 2"
      try:
         subprocess.check_call(cmd.split(), timeout=120)
      except subprocess.TimeoutExpired as e:
         log.error("Ledger API test tool timed out: %s", str(e))
         log.error(str(e.output))
         return (False, str(e))
      except subprocess.CalledProcessError as e:
         log.error("Ledger API test tool returned an error: %s", str(e))
         log.error(str(e.output))
         return (False, str(e))

      log.info("Semantic test runner passed.")
      return (True, None)
