# Copyright 2019 VMware, Inc.  All rights reserved. -- VMware Confidential

# HLF needs a special docker-compose and concord configuration file to test.
# Also, the HlfTest uses a script to start the product rather than use the
# docker-compose file directly.
#
# python3 main.py --runConcordConfigurationGeneration --concordConfigurationInput /concord/config/dockerConfigurationInput-hlf.yaml --dockerComposeFile=../docker/docker-compose-hlf.yml HlfTests

# The tests will login to the Concord node, call "conc_hlf_client" tool to send request and check the response.

import logging
import os
import traceback
import subprocess
import time

from . import test_suite
from util.product import Product

log = logging.getLogger(__name__)

# The status is set by the kvb command handler.
StatusOk = "status: 0"
StatusFailed = "status: 1"

class HlfProduct(Product):
   def _waitForProductStartup(self):
      '''Wait for HLF peer to be up and running.
      '''

      LOG_READY = "Successfully submitted proposal to join channel"
      LOG_FILE = self._createLogPath('cli{}_1')

      # Wait for all peers to create channel
      LOG_READY_COUNT = 0

      for i in range(1, 5):
         with open(LOG_FILE.format(str(i))) as f:
            timeout = 0
            while timeout < 10:
               if any(list(filter(lambda x: LOG_READY in x, f.readlines()))):
                   LOG_READY_COUNT += 1
                   break
               time.sleep(1)
               timeout += 1

      if LOG_READY_COUNT == 4:
         return True
      else:
         log.error("HLF product didn't start in time. %s",
                   self._createLogPath(LOG_FILE.format(i)))
         return False

class HlfTests(test_suite.TestSuite):

   def __init__(self, passedArgs):
      super(HlfTests, self).__init__(passedArgs)

   def getName(self):
      return "HlfTests"

   def launchProduct(self, cmdlineArgs, userConfig, force=False):
      '''Launch the HLF product.
      '''
      self.product = HlfProduct(cmdlineArgs, userConfig)

      if force or (self._productMode and not self._noLaunch):
         try:
            self.product.launchProduct()
         except Exception as e:
            log.error(str(e))
            self.writeResult("All Tests", False, "The product did not start.")
            raise

   def run(self):
      '''Runs all tests
      '''
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

      log.info("HLFTests are done.")
      return self._resultFile

   def _getTests(self):
      return [("chaincode_fabcar_install", self._test_chaincode_fabcar_install),
              ("chaincode_fabcar_invoke_init_ledger",
                  self._test_chaincode_fabcar_invoke_init_ledger),
              ("chaincode_fabcar_invoke_change_car_owner",
                  self._test_chaincode_fabcar_invoke_change_car_owner),
              ("chaincode_fabcar_invoke_create_car",
                  self._test_chaincode_fabcar_invoke_create_car),
              ("chaincode_fabcar_query_history",
                  self._test_chaincode_fabcar_query_history),
              ("chaincode_fabcar_query_range_state",
                  self._test_chaincode_fabcar_query_range_state),
              ("chaincode_fabcar_upgrade", self._test_chaincode_fabcar_upgrade)
              ]

   def _test_chaincode_fabcar_install(self):
       '''
       Test the installation of chaincode "fabcar"

       The test cases after this one will test chaincode invocation and query against version 1.0.

       The expected output is as follow:
         '{"data": "Successfully installed chaincode: mycc", "status": 0}'

       Where "mycc" is the chaincode name, and "status" is set to zero if success.
       Any error hanppens during the execution of chaincode will cause the "status"
       to no-zero value.

       For more details about the chaincode life cycle management, please refer to
       this article: 
       https://hyperledger-fabric.readthedocs.io/en/release-1.3/chaincode4noah.html
       '''

       cmds = ["docker exec -t concord-client " \
             "./conc_hlf_client -a concord1 -p 50051 -i {\"Args\":[\"init\"]} " \
             "-f src/chaincode/fabcar/go/fabcar.go " \
             "-m install -c mycc -v 1.0"]

       for cmd in cmds:
          try:
             c = subprocess.run(cmd.split(), check=True, timeout=1000, stdout=subprocess.PIPE)
          except subprocess.TimeoutExpired as e:
             log.error("Chaincode install timeout: %s", str(e))
             return (False, str(e))
          except subprocess.CalledProcessError as e:
             log.error("Chaincode install returned error code: %s", str(e))
             return (False, str(e))

          result = str(c.stdout)
          log.debug(result)
          if StatusOk not in result:
             return (False, "Concord returned Non-zero status.")

       return (True, None)

   def _test_chaincode_fabcar_upgrade(self):
       '''
       Test the upgrade of the chaincode "fabcar"

       This test will upgrade the version of chaincode "fabcar" from "1.0" to "2.0".
       It will again trigger the build of chaincode image with new version, and 
       then run the container to host the chaincode process. Also, the container
       with preivous version will be removed.

       The expected output is as follow:
         '{"data": "Successfully upgraded chaincode: mycc", "status": 0}'
       '''

       cmd = "docker exec -t concord-client " \
             "./conc_hlf_client -a concord1 -p 50051 -m upgrade " \
             "-f src/chaincode/fabcar/go/fabcar.go -c mycc -i {\"Args\":[\"init\"]} -v 2.0"
       try:
          c = subprocess.run(cmd.split(), check=True, timeout=10000, stdout=subprocess.PIPE)
       except subprocess.TimeoutExpired as e:
          log.error("Chaincode upgrade timeout: %s", str(e))
          return (False, str(e))
       except subprocess.CalledProcessError as e:
          log.error("Chaincode upgrade returned error code: %s", str(e))
          return (False, str(e))

       result = str(c.stdout)
       log.debug(result)
       if StatusOk in result:
          return (True, None)
       else:
          return (False, "Concord returned Non-zero status.")

   def _test_chaincode_fabcar_invoke_init_ledger(self):
       '''
       Test the Invocation of the chaincode "fabcar"

       This test will call "initLedger" function defined in chaincode "fabcar".
       It will create a batch of key value pairs formatting as follows:
         {
          CAR0:{Make: "Toyota", Model: "Prius", Colour: "blue", Owner: "Tomoko"},
          CAR1:{Make: "Ford", Model: "Mustang", Colour: "red", Owner: "Brad"},
          ...
         }

       The expected output is as follow:
         '{"data": "Successfully invoked chaincode: mycc", "status": 0}'

       If invoke successfully, then call chaincode query to verify the data.
       '''

       # sleep 3 secodes for peer to commit the block
       time.sleep(3)

       cmd = "docker exec -t concord-client " \
             "./conc_hlf_client -a concord1 -p 50051 -m invoke -c mycc -i {\"Function\":\"initLedger\",\"Args\":[]}"
       try:
          c = subprocess.run(cmd.split(), check=True, timeout=1000, stdout=subprocess.PIPE)
       except subprocess.TimeoutExpired as e:
          log.error("Chaincode invoke timeout: %s", str(e))
          return (False, str(e))
       except subprocess.CalledProcessError as e:
          log.error("Chaincode invoke returned error code: %s", str(e))
          return (False, str(e))

       result = str(c.stdout)
       log.debug(result)
       if StatusOk in result:
           return self._test_chaincode_query(
                   "{\"function\":\"queryCar\",\"Args\":[\"CAR0\"]}",
                   "\"owner\":\"Tomoko\"")
       else:
          return (False, "Concord returned Non-zero status.")
       return (True, None)

   def _test_chaincode_fabcar_invoke_change_car_owner(self):
       '''
       Test the Invocation of the chaincode "fabcar"

       This test will call "changeCarOwner" function defined in chaincode "fabcar" to 
       change the "Owner" of "CAR0" from "Tomoko" to "Luke".

       The expected output is as follow:
         '{"data": "Successfully invoked chaincode: mycc", "status": 0}'

       If invoke successfully, then call chaincode query to verify the data.
       '''

       cmd = "docker exec -t concord-client " \
             "./conc_hlf_client -a concord1 -p 50051 -m invoke -c mycc -i {\"Function\":\"changeCarOwner\",\"Args\":[\"CAR0\",\"Luke\"]}"
       try:
          c = subprocess.run(cmd.split(), check=True, timeout=1000, stdout=subprocess.PIPE)
       except subprocess.TimeoutExpired as e:
          log.error("Chaincode invoke timeout: %s", str(e))
          return (False, str(e))
       except subprocess.CalledProcessError as e:
          log.error("Chaincode invoke returned error code: %s", str(e))
          return (False, str(e))

       result = str(c.stdout)
       log.debug(result)
       if StatusOk in result:
          return self._test_chaincode_query(
                  "{\"function\":\"queryCar\",\"Args\":[\"CAR0\"]}",
                  "\"owner\":\"Luke\"")
       else:
          return (False, "Concord returned Non-zero status.")
       return (True, None)

   def _test_chaincode_fabcar_invoke_create_car(self):
       '''
       Test the Invocation of the chaincode "fabcar"

       This test will call "createCar" function defined in chaincode "fabcar" to 
       create key value pair:
         {CAR99: {Make: "Make99", Model: "Model99", Colour: "Colour99", Owner: "Owner99"}}

       The expected output is as follow:
         '{"data": "Successfully invoked chaincode: mycc", "status": 0}'

       If invoke successfully, then call chaincode query to verify the data.
       '''

       cmd = "docker exec -t concord-client " \
             "./conc_hlf_client -a concord1 -p 50051 -m invoke -c mycc -i {\"Function\":\"createCar\",\"Args\":[\"CAR99\",\"Make99\",\"Model99\",\"Colour99\",\"Owner99\"]}"
       try:
          c = subprocess.run(cmd.split(), check=True, timeout=1000, stdout=subprocess.PIPE)
       except subprocess.TimeoutExpired as e:
          log.error("Chaincode invoke timeout: %s", str(e))
          return (False, str(e))
       except subprocess.CalledProcessError as e:
          log.error("Chaincode invoke returned error code: %s", str(e))
          return (False, str(e))

       result = str(c.stdout)
       log.debug(result)
       if StatusOk in result:
          return self._test_chaincode_query(
                  "{\"function\":\"queryCar\",\"Args\":[\"CAR99\"]}",
                  "\"owner\":\"Owner99\"")
       else:
          return (False, "Concord returned Non-zero status.")
       return (True, None)

   def _test_chaincode_query(self, query_input, expected_result=StatusOk):
       '''
       Test the Query of the chaincode "fabcar"

       Query the value of a specified key and use the "expected_result" to
       verify the result.

       '''
       cmd = "docker exec -t concord-client " \
             "./conc_hlf_client -a concord1 -p 50051 -m query -c mycc -i " + query_input
       try:
          c = subprocess.run(cmd.split(), check=True, timeout=1000, stdout=subprocess.PIPE)
       except subprocess.TimeoutExpired as e:
          log.error("Chaincode query timeout: %s", str(e))
          return (False, str(e))
       except subprocess.CalledProcessError as e:
          log.error("Chaincode query returned error code: %s", str(e))
          return (False, str(e))

       result = str(c.stdout)
       log.debug(result)
       if expected_result in result:
          return (True, None)
       else:
          return (False, "Concord returned Non-zero status.")

   def _test_chaincode_fabcar_query_history(self):
    '''
    Test the Query History of the chaincode "fabcar"

    Query the historical value of the "CAR0" key.

    '''
    cmd = "docker exec -t concord-client " \
          "./conc_hlf_client -a concord1 -p 50051 -m query -c mycc -i {\"function\":\"queryCarHistory\",\"Args\":[\"CAR0\"]}" 
    try:
       c = subprocess.run(cmd.split(), check=True, timeout=1000, stdout=subprocess.PIPE)
    except subprocess.TimeoutExpired as e:
       log.error("Chaincode query timeout: %s", str(e))
       return (False, str(e))
    except subprocess.CalledProcessError as e:
       log.error("Chaincode query returned error code: %s", str(e))
       return (False, str(e))

    result = str(c.stdout)
    log.debug(result)
    if "Tomoko" in result and "Luke" in result:
       return (True, None)
    else:
       return (False, "Concord returned Non-zero status.")

   def _test_chaincode_fabcar_initial(self):
       '''
       Test the "initial" API

       "initial" combines upload, install, instantiate three steps into one.
       '''
       cmd = "docker exec -t concord-client " \
             "./conc_hlf_client -p 50051 -a concord1 -m initial -c mycc2 -i {\"Function\":\"initLedger\",\"Args\":[]} \
             -v 1.0 -f src/chaincode/fabcar/go/fabcar.go"
       try:
          c = subprocess.run(cmd.split(), check=True, timeout=1000, stdout=subprocess.PIPE)
       except subprocess.TimeoutExpired as e:
          log.error("Chaincode initial timeout: %s", str(e))
          return (False, str(e))
       except subprocess.CalledProcessError as e:
          log.error("Chaincode initial returned error code: %s", str(e))
          return (False, str(e))

       result = str(c.stdout)
       log.debug(result)
       if StatusFailed not in result:
          return (True, None)
       else:
          return (False, "Concord returned Non-zero status.")

   def _test_chaincode_fabcar_query_range_state(self):
       '''
       Test the GetStateByRange API
    
       Peform the range query against key "CAR0" ~ "CAR99".
    
       '''
       cmd = "docker exec -t concord-client " \
             "./conc_hlf_client -a concord1 -p 50051 -m query -c mycc -i {\"function\":\"queryAllCars\",\"Args\":[\"\"]}" 
       try:
          c = subprocess.run(cmd.split(), check=True, timeout=1000, stdout=subprocess.PIPE)
       except subprocess.TimeoutExpired as e:
          log.error("Chaincode query timeout: %s", str(e))
          return (False, str(e))
       except subprocess.CalledProcessError as e:
          log.error("Chaincode query returned error code: %s", str(e))
          return (False, str(e))
    
       result = str(c.stdout)
       log.debug(result)

       for i in range(10):
           if "CAR" + str(i) in result:
               continue
           else:
               return (False, "Unable to Pass the GetStateByRange test")

       return (True, None)