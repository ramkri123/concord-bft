#########################################################################
# Copyright 2018 VMware, Inc.  All rights reserved. -- VMware Confidential
#
# Tests covering BeerWars DApp compatibility.
#########################################################################


#########################################################################
# Example executions
# 1)
# ./main.py BeerWarsTests --endpoint='https://mgmt.blockchain.vmware.com/
# blockchains/c3e4c911-9f9d-4899-9c92-6ced72d3ded3/api/
# concord/eth' --user='admin@blockchain.local' --password='Passw0rd!'
# 2)
# ./main.py BeerWarsTests
#########################################################################

import collections
import json
import logging
import os
import traceback
import string
import random
import time
import subprocess
from time import sleep

from . import test_suite
from util.product import Product
from rest.request import Request
from rpc.rpc_call import RPC

import util.json_helper

log = logging.getLogger(__name__)

class BeerWarsTests(test_suite.TestSuite):
   _args = None
   _apiBaseServerUrl = "https://localhost/blockchains/local"
   _userConfig = None
   _ethereumMode = False
   _productMode = True
   _resultFile = None
   _user = None
   _password = None

   def __init__(self, passedArgs):
      super(BeerWarsTests, self).__init__(passedArgs)
      self._args = passedArgs

      if self._args.user != None:
         self._user = self._args.user
      else:
         self._user = "admin@blockchain.local"

      if self._args.password != None:
         self._password = self._args.password
      else:
         self._password = "Admin!23"

      # Test does not launch the product if a URL is passed to it
      if self._args.endpoint != None:
         self._apiServerUrl = self._args.endpoint
         self._noLaunch = True
      else:
         self._apiServerUrl = "http://URL_PLACEHOLDER:8080/api/concord/eth"

      if self._ethereumMode:
         self._noLaunch = True


   def getName(self):
      return "BeerWarsTests"


   def _runTest(self, testName, testFun, testLogDir):
      log.info("Starting test '{}'".format(testName))
      fileRoot = os.path.join(self._testLogDir, testName);
      return testFun(fileRoot)


   def run(self):
      ''' Runs all of the tests. '''
      if self._productMode and not self._noLaunch:
         try:
            p = self.launchProduct(self._args,
                                   self._apiBaseServerUrl + "/api/concord/eth",
                                   self._userConfig)
         except Exception as e:
            log.error(traceback.format_exc())
            return self._resultFile

      self._cleanUp()
      tests = self._getTests()

      for (testName, testFun) in tests:
         testLogDir = os.path.join(self._testLogDir, testName)
         try:
            result, info = self._runTest(testName,
                                         testFun,
                                         testLogDir)
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

      log.info("Tests are done.")
      self._cleanUp()
      return self._resultFile


   def _getTests(self):
      return [("beerwars", self._test_beerwars)]

   def _executeInContainer(self, command):
      p = subprocess.Popen(command, shell=True, stdout=subprocess.PIPE)
      data, err = p.communicate()
      if err != None:
         return (None, err)
      out = data.strip().decode('utf-8')
      return (out, None)

   def _concatenatedExecuteInContainer(self, command1, command2):
      ''' Equivalent to the following steps :
             1. Execute command2
             2. If errors in 1., exit
             3. If non-empty output in 1., execute command1 with the output as STDIN
      '''
      out1, err1 =self. _executeInContainer(command2)
      if out1 != None and out1 != '':
         return self._executeInContainer(command1 + " " + out1)
      return (out1, err1)

   def _cleanUp(self):
      ''' Cleaning up the Docker changes the test(s) caused '''
      log.info("Cleaning up")
      self._concatenatedExecuteInContainer("docker stop","docker ps | grep beerwars | sed 's/|/ /' | awk '{print $1}'")
      self._concatenatedExecuteInContainer("docker rm -f", "docker ps -a | grep beerwars | sed 's/|/ /' | awk '{print $1}'")
      self._concatenatedExecuteInContainer("docker rmi", "docker images | grep beerwars | sed 's/|/ /' | awk '{print $3}'")


   def _test_beerwars(self, fileRoot):
      ''' Tests if BeerWars can be deployed using the docker container '''
      out, err = self._executeInContainer("docker run --name beerwars-test --network='host' -td mmukundram/beerwars:latest")
      if err != None:
         return (False, err)

      # The endpoint for the DApp to be deployed is the host of the container
      # Following command is to get the IP address of the host in the host-container network
      out, err = self._executeInContainer("ifconfig docker | grep 'inet addr' | cut -d: -f2 | cut -d ' ' -f1 | awk '{print $1}'")
      self._apiServerUrl = self._apiServerUrl.replace("URL_PLACEHOLDER", out)

      if self._apiServerUrl != '':
         pass_endpoint = self._apiServerUrl.replace('/','\/');

         # Edit placeholders with actual values inside the container
         comm = 'docker exec beerwars-test sed -i -e \'s/ADDRESS_PLACEHOLDER/' + pass_endpoint + '/g\' test/test_BeerWars.js'
         out1, err1 = self._executeInContainer(comm)
         if err1 != None:
            return (False, err1)

         out2, err2 = self._executeInContainer("docker exec beerwars-test sed -i -e 's/USER_PLACEHOLDER/" + self._user + "/g' test/test_BeerWars.js")
         if err2 != None:
            return (False, err2)

         out3, err3 = self._executeInContainer("docker exec beerwars-test sed -i -e 's/PASSWORD_PLACEHOLDER/" + self._password + "/g' test/test_BeerWars.js")
         if err3 != None:
            return (False, err3)

      # Run the test script(s)
      out, err = self._executeInContainer("docker exec beerwars-test mocha")
      if err != None:
         return (False, err)

      return (True, None)
