###############################################################################
# Copyright 2019 VMware, Inc.  All rights reserved. -- VMware Confidential
#
# This is the parent class for Hermes test suites which use PyTest.
###############################################################################
import json
import logging
import os
import pytest
import traceback

import util.json_helper
from . import test_suite

log = logging.getLogger(__name__)

class PytestSuite(test_suite.TestSuite):

   def __init__(self, passedArgs, testFile):
       self._reportFile = "report.json"
       self._testFile = testFile
       super(PytestSuite, self).__init__(passedArgs)


   def getName(self):
       return self._testFile.replace(os.sep, "_")


   def run(self):
      '''
      Runs the tests passed in, parses PyTest's json output and rewrites
      it in Hermes's format, and returns the path to that file.
      '''
      try:
         self.launchProduct(self._args,
                            self._userConfig)
      except Exception as e:
         log.error(traceback.format_exc())
         return self._resultFile

      os.environ["PYTHONDONTWRITEBYTECODE"] = "1"

      # Notes on PyTest command line usage:
      # -m "performance and smoke" will run tests which are both performance and smoke.
      # -m performance -m smoke will run all peformance tests and all smoke tests.
      cmdlineArgsJson = json.dumps(vars(self._args))
      userConfigJson = json.dumps(self._userConfig)
      params = ["--capture=no", "--verbose", "--json", self._reportFile,
                "--hermesCmdlineArgs", cmdlineArgsJson,
                "--hermesUserConfig", userConfigJson,
                "--hermesTestLogDir", self._testLogDir,
                self._testFile]

      if self._args.tests:
         params += self._args.tests.split(" ")

      pytest.main(params)
      return self.parsePytestResults()


   def parsePytestResults(self):
      '''
      Convert PyTest's json format to the Hermes format for Hermes
      to parse later.
      '''
      results = util.json_helper.readJsonFile(self._reportFile)
      for testResult in results["report"]["tests"]:
         passed = None

         if testResult["outcome"] == "passed" or testResult["outcome"] == "xfailed":
            passed = True
         elif testResult["outcome"] == "skipped":
            passed = "skipped"
         else:
            passed = False

         info = "" if passed else json.dumps(testResult, indent=2)
         testName = self.parsePytestTestName(testResult["name"])
         testLogDir = os.path.join(self._testLogDir, testName)
         relativeLogDir = self.makeRelativeTestPath(testLogDir)
         info += "\nLog: <a href=\"{}\">{}</a>".format(relativeLogDir,
                                                     testLogDir)
         self.writeResult(testResult["name"],
                          passed,
                          info)

      if self._shouldStop():
         self.product.stopProduct()

      return self._resultFile


   def parsePytestTestName(self, parseMe):
      '''
      Returns a condensed name, just used to reduce noise.
      '''
      return parseMe[parseMe.rindex(":")+1:]
