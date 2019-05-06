#!/usr/bin/python3

#########################################################################
# Copyright 2018 - 2019 VMware, Inc.  All rights reserved. -- VMware Confidential
#########################################################################

import argparse
import datetime
import logging
import os
import tempfile
from time import strftime, localtime

from suites import (asset_transfer_tests, contract_compiler_tests, core_vm_tests,
                    ext_rpc_tests, lint_e2e_tests, helen_api_tests, performance_tests, persephone_tests,
                    regression_tests, simple_st_test, truffle_tests, ui_tests)

from util import html, json_helper

log = None
suites = ["AssetTransferTests", "ContractCompilerTests", "CoreVMTests",
          "LintTests", "ExtendedRPCTests", "HelenAPITests", "PerformanceTests", "PersephoneTests",
          "RegressionTests", "SimpleStateTransferTest", "TruffleTests", "UiTests"]

def main():
   startTime = datetime.datetime.now()
   parser = argparse.ArgumentParser()
   parser.add_argument("suite", help="Test suite name.  Available suites: {}". \
                       format(suites))
   parser.add_argument("--ethereumMode",
                       help="Run tests against Ethereum",
                       default=False,
                       action='store_true')
   parser.add_argument("--logLevel",
                       help="Set the log level.  Valid values:"
                       "'DEBUG', 'INFO', 'WARNING', 'ERROR', 'CRITICAL'",
                       default="INFO")
   parser.add_argument("--resultsDir",
                       default=tempfile.gettempdir(),
                       help="Results directory")
   parser.add_argument("--tests",
                       help="Run specific tests. Details depend on the suite " \
                       "being run. For CoreVMTests, this is a directory or " \
                       "specific file relative to the VMTests directory. e.g. " \
                       "'--tests vmArithmeticTest' or " \
                       "'--tests vmArithmeticTest/add0.json'")
   parser.add_argument("--config",
                       help="User config file to be considered.")
   parser.add_argument("--dockerComposeFile",
                       help="REQUIRES SUDO. Accepts a docker compose file " \
                       "which starts concord and helen.  The product will " \
                       "be launched in docker images instead of on the " \
                       "command line.  May be a space-separated list of files, " \
                       "in the order in which the files should be applied.",
                       default=["../docker/docker-compose.yml"],
                       nargs="*")
   parser.add_argument("--noLaunch",
                       default=False,
                       action='store_true',
                       help="Will not launch the product, assuming it is "
                            "already running")
   parser.add_argument("--productLaunchAttempts",
                       help="Number of times to attempt to launch the product " \
                       "before failing.  Used to work around intermittent bugs " \
                       "with product startup.",
                       default=1,
                       type=int)
   parser.add_argument("--keepconcordDB",
                       help="Keep and re-use the existing concord database files.",
                       default=False,
                       action='store_true')
   parser.add_argument("--repeatSuiteRun",
                       default=1,
                       type=int,
                       help="Number of times to repeat test runs")
   parser.add_argument("--endpoint",
                       help="Endpoint for AssetTransfer tests")
   parser.add_argument("--user",
                       help="User name for AssetTransfer tests")
   parser.add_argument("--password",
                       help="Password for AssetTransfer tests")
   parser.add_argument("--reverseProxyApiBaseUrl",
                       default="https://localhost/blockchains/local",
                       help="Base URL for Helen REST API calls. Test cases drill "
                            "down further into the API with values such as '/api/users', "
                            "'/api/concord/blocks', '/api/concord/eth', etc...).")
   parser.add_argument("--inDockerReverseProxyApiBaseUrl",
                       default="https://reverse-proxy/blockchains/local",
                       help="Base URL for accessing the reverse proxy server from within "
                            "the docker environment.")
   parser.add_argument("--ethrpcApiUrl",
                       default=None,
                       help="By default, Helen's getMembers API is used to fetch ethrpc nodes, "
                            "and test cases randomly select nodes from that pool.  To force use "
                            "of one node, or to use an official Ethereum setup, specify its "
                            "url here.  e.g. 'http://localhost:8545'")
   parser.add_argument("--contractCompilerApiBaseUrl",
                       default="http://localhost:3000/api/v1",
                       help="Base URL for the contract compiler microservice")
   parser.add_argument("--runConcordConfigurationGeneration",
                       help="Run Concord configuration generation for the test"
                            " cluster before launching and launch with the"
                            " newly generated configuration files. If this"
                            " option is not given, then configuration"
                            " generation will be skipped and the currently"
                            " existing configuration files will be used.",
                       default=False,
                       action='store_true')

   args = parser.parse_args()
   parent_results_dir = args.resultsDir

   dir_path = os.path.dirname(os.path.realpath(__file__))
   # In future, if the location of main.py changes from hermes/,
   # update args.hermes_dir accordingly
   args.hermes_dir = dir_path

   setUpLogging(args)
   for run_count in range(1, args.repeatSuiteRun+1):
      log.info("\nTestrun: {0}/{1}".format(run_count, args.repeatSuiteRun))
      log.info("Start time: {}".format(startTime))
      args.resultsDir = createResultsDir(args.suite,
                                         parent_results_dir=parent_results_dir)
      log.info("Results directory: {}".format(args.resultsDir))
      suite = createTestSuite(args)
      if suite is None:
         log.error("Unknown test suite")
         exit(3)

      log.info("Running suite {}".format(suite.getName()))
      success = processResults(suite.run())
      endTime = datetime.datetime.now()
      log.info("End time: {}".format(endTime))
      log.info("Elapsed time: {}".format(str(endTime - startTime)))
      if not success:
         update_repeated_suite_run_result(parent_results_dir, "fail", args.repeatSuiteRun)
         exit(2)

      if args.repeatSuiteRun > 1:
         args.noLaunch = True

   update_repeated_suite_run_result(parent_results_dir, "pass", args.repeatSuiteRun)

def update_repeated_suite_run_result(parent_results_dir, result, no_of_runs):
   if no_of_runs > 1:
      result_file = os.path.join(parent_results_dir,
                                 'test_status.{0}'.format(result))
      log.info("Repeated Suite run result: {0} [{1}]".format(result, result_file))
      open(result_file, 'a').close()

def setUpLogging(args):
   '''
   Checks the log level passed in by the user and sets up logging.
   After running, get a logger and use it like this:

   log = logging.getLogger(__name__)
   log.info("Two things: {} {}".format("pen", "pencil"))
   '''
   stringLevel = args.logLevel.upper()

   try:
      intLevel = getattr(logging, stringLevel)
      logging.basicConfig(level=intLevel, format='%(message)s')
      global log
      log = logging.getLogger(__name__)
   except AttributeError:
      print("Log level", args.logLevel, "not valid.  See the help for valid",
            "values. Exiting")
      exit(1)

def createTestSuite(args):
   if (args.suite == "AssetTransferTests"):
      return asset_transfer_tests.AssetTransferTests(args)
   elif (args.suite == "ContractCompilerTests"):
       return contract_compiler_tests.ContractCompilerTests(args)
   elif (args.suite == "CoreVMTests"):
      return core_vm_tests.CoreVMTests(args)
   elif (args.suite == "HelenAPITests"):
      return helen_api_tests.HelenAPITests(args)
   elif (args.suite == "ExtendedRPCTests"):
      return ext_rpc_tests.ExtendedRPCTests(args)
   elif (args.suite == "PerformanceTests"):
      return performance_tests.PerformanceTests(args)
   elif (args.suite == "PersephoneTests"):
      return persephone_tests.PersephoneTests(args)
   elif (args.suite == "RegressionTests"):
      return regression_tests.RegressionTests(args)
   elif (args.suite == "SimpleStateTransferTest"):
      return simple_st_test.SimpleStateTransferTest(args)
   elif (args.suite == "TruffleTests"):
      return truffle_tests.TruffleTests(args)
   elif (args.suite == "UiTests"):
      return ui_tests.UiTests(args)
   elif (args.suite == "LintTests"):
      return lint_e2e_tests.LintTests(args)
   else:
      return None

def createResultsDir(suiteName, parent_results_dir=tempfile.gettempdir()):
   prefix = suiteName + "_" + strftime("%Y%m%d_%H%M%S", localtime())
   results_dir = os.path.join(parent_results_dir, prefix)
   os.makedirs(results_dir)
   return results_dir

def processResults(resultsFile):
   '''
   Process a result file, outputting an html file.  If we ever need to output
   a file in another format (such as a CI tool), do that here.
   '''
   log.debug("Processing results for '{}'".format(resultsFile))
   results = json_helper.readJsonFile(resultsFile)
   testCount, passCount, failCount, skippedCount = tallyResults(results)
   fileContents = html.createResultHeader(results,
                                          testCount,
                                          passCount,
                                          failCount,
                                          skippedCount)
   fileContents += html.createResultTable(results)
   fileContents += html.createHtmlFooter()
   fileLocation = os.path.join(os.path.dirname(resultsFile), "results.html")

   with open(fileLocation, "w") as f:
      f.write(fileContents)

   log.info("Results written to '{}'".format(fileLocation))

   suiteName = list(results.keys())[0]
   red = "\033[1;31m"
   green = "\033[1;32m"
   yellow = "\033[1;33m"
   reset = "\033[0m"

   msg = "{color}{symbol} " + suiteName + " {tests}" + reset

   if failCount > 0:
      msg = msg.format(color=red, symbol="\u2717",
                       tests="{} tests failed".format(failCount))
   else:
      msg = msg.format(color=green, symbol="\u2714",
                       tests="{} tests succeeded".format(passCount))

   if skippedCount > 0:
      msg += " {}{} skipped{}".format(yellow, skippedCount, reset)

   log.info(msg)

   return failCount == 0 and skippedCount == 0

def tallyResults(results):
   '''
   Given the results structure, returns:
   - Total number of tests run.
   - Passes
   - Failures
   - Skipped
   '''
   suiteName = list(results.keys())[0]
   testCases = results[suiteName]["tests"]
   passCount = 0
   failCount = 0
   skippedCount = 0
   totalCount = 0

   for test in testCases:
      totalCount += 1

      if testCases[test]["result"] == "PASS":
         passCount += 1
      elif testCases[test]["result"] == "FAIL":
         failCount += 1
      elif testCases[test]["result"] == "SKIPPED":
         skippedCount += 1

   return totalCount, passCount, failCount, skippedCount

main()
