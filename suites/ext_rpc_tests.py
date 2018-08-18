#########################################################################
# Copyright 2018 VMware, Inc.  All rights reserved. -- VMware Confidential
#
# Test the parts of the Ethereum JSON RPC API beyond what the
# CoreVMTests cover. This checks things like web3_sha3,
# eth_clientVersion, eth_mining, ...
#########################################################################
import argparse
import collections
import json
import logging
import os
import pprint
import tempfile
import time
import traceback
import re

from . import test_suite
from rpc.rpc_call import RPC
from rest.request import Request
from util.debug import pp as pp
from util.numbers_strings import trimHexIndicator, decToEvenHexNo0x
from util.product import Product
import util.json_helper

log = logging.getLogger(__name__)

class ExtendedRPCTests(test_suite.TestSuite):
   _args = None
   _apiBaseServerUrl = None
   _apiServerUrl = None
   _userConfig = None
   _ethereumMode = False
   _productMode = True
   _resultFile = None
   _unintentionallySkippedFile = None
   _userUnlocked = False

   def __init__(self, passedArgs):
      super(ExtendedRPCTests, self).__init__(passedArgs)

      if self._ethereumMode:
         log.debug("Running in ethereum mode")
         self._apiServerUrl = "http://localhost:8545"
      else:
         self._apiBaseServerUrl = "http://localhost:8080"
         self._apiServerUrl = self._apiBaseServerUrl+"/api/athena/eth/"

   def getName(self):
      return "ExtendedRPCTests"

   def run(self):
      ''' Runs all of the tests. '''
      if self._productMode:
         try:
            p = self.launchProduct(self._args.resultsDir,
                                   self._apiServerUrl,
                                   self._userConfig["product"])
         except Exception as e:
            log.error(traceback.format_exc())
            return self._resultFile

      tests = self._getTests()

      for (testName, testFun) in tests:
         testLogDir = os.path.join(self._testLogDir, testName)

         try:
            result, info = self._runRpcTest(testName,
                                            testFun,
                                            testLogDir)
         except Exception as e:
            result = False
            info = str(e) + "\n" + traceback.format_exc()
            log.error("Exception running RPC test: '{}'".format(info))

         if info:
            info += "  "
         else:
            info = ""

         relativeLogDir = self.makeRelativeTestPath(testLogDir)
         info += "Log: <a href=\"{}\">{}</a>".format(relativeLogDir,
                                                     testLogDir)
         self.writeResult(testName, result, info)

      log.info("Tests are done.")

      if self._productMode:
         p.stopProduct()

      return self._resultFile

   def _getTests(self):
      return [("web3_sha3", self._test_web3_sha3), \
              ("web3_clientVersion", self._test_web3_clientVersion), \
              ("eth_mining", self._test_eth_mining), \
              ("rpc_modules", self._test_rpc_modules), \
              ("eth_getTransactionCount", self._test_eth_getTransactionCount), \
              ("eth_sendRawTransaction", self._test_eth_sendRawTransaction), \
              ("eth_sendRawContract", self._test_eth_sendRawContract)]

   def _runRpcTest(self, testName, testFun, testLogDir):
      ''' Runs one test. '''
      log.info("Starting test '{}'".format(testName))
      rpc = RPC(testLogDir,
                testName,
                self._apiServerUrl)
      request = Request(testLogDir, testName, self._apiBaseServerUrl)
      return testFun(rpc, request)

   def _test_web3_sha3(self, rpc, request):
      '''
      Check that hashing works as expected.
      '''
      # list of (data, expected hash) tuples
      datahashes = [("0x", "0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"), \
                    ("0x1234567890abcdef", "0xed8ab4fde4c4e2749641d9d89de3d920f9845e086abd71e6921319f41f0e784f")]

      for (d, h) in datahashes:
         result = rpc.sha3(d)
         if not result == h:
            return (False,
                    "Hash of '{}' did not match" \
                    "expected '{}': actual: '{}'".format(d, h, result))

      return (True, None)

   def _test_web3_clientVersion(self, rpc, request):
      '''
      Check that we return a valid version
      '''
      result = rpc.clientVersion()
      if not type(result) is str:
         return (False, "Client version should have been a string, " \
                 "but was '{}'".format(result))

      # Insisting version is
      # <name>/v<major>.<minor>.<patch><anything>/
      #    <os>/<language><major>.<minor>.<patch>
      version_re = re.compile("\\w+/v\\d+\\.\\d+\\.\\d+[^/]*/"\
                              "[-a-zA-Z0-9]+/\\w+\\d\\.\\d\\.\\d")
      if not version_re.match(result):
         return (False, "Client version doesn't match expected format: " \
                 "'{}'".format(result))

      return (True, None)

   def _test_eth_mining(self, rpc, request):
      '''
      Check that mining status is reported correctly
      '''
      result = rpc.mining()
      if self._ethereumMode and (not result == True):
         return (False, "Expected ethereumMode to be mining, " \
                 "but found '{}'".format(result))
      elif self._productMode and (not result == False):
         return (False, "Expected product to not be mining, " \
                 "buf found '{}'".format(result))

      return (True, None)

   def _test_rpc_modules(self, rpc, request):
      '''
      Check that available RPC modules are listed correctly
      '''
      result = rpc.modules()

      if not type(result) is collections.OrderedDict:
         return (False, "Reply should have been a dict.")

      # This means the test is invalid, but let's not blow up because of it
      if len(result) == 0:
         log.warn("No RPC modules returned from rpc request.")

      # Insisting the version is <number>.<number>
      version_re = re.compile("\\d+\\.\\d+")

      for k, v in result.items():
         if not k in ["admin", "eth", "miner", "net", "personal", "rpc", "web3"]:
            return (False,
                    "Response included unknown RPC module '{}'".format(k))
         if not version_re.match(v):
            return (False,
                    "Module version should be version like, " \
                    "but was '{}'".format(v))

      return (True, None)

   def _test_eth_getTransactionCount(self, rpc, request):
      '''
      Check that transaction count is updated.
      '''
      newAccount = None
      for i in range(0,10):
         try:
            newAccount = rpc.newAccount("gettxcount{}".format(i))
            if newAccount:
               break
         except:
            pass

      if not newAccount:
         return (False, "Unable to create new account")

      startNonce = rpc.getTransactionCount(newAccount)
      if not startNonce:
         return (False, "Unable to get starting nonce")

      if not startNonce == "0x0000000000000000000000000000000000000000000000000000000000000000":
         return (False, "Start nonce was not zero (was {})".format(startNonce))

      txResult = rpc.sendTransaction(newAccount,
                                     data = "0x00",
                                     gas = "0x01")
      if not txResult:
         return (False, "Transaction was not accepted")

      endNonce = rpc.getTransactionCount(newAccount)
      if not endNonce:
         return (False, "Unable to get ending nonce")

      if not endNonce == "0x0000000000000000000000000000000000000000000000000000000000000001":
         return (False, "End nonce was not 1 (was {})".format(endNonce))

      return (True, None)

   def _test_eth_sendRawTransaction(self, rpc, request):
      '''
      Check that a raw transaction gets decoded correctly.
      '''

      # known transaction from public ethereum
      # https://etherscan.io/tx/0x6ab11d26df13bc3b2cb1c09c4d274bfce325906c617d2bc744b45fa39b7f8c68
      rawTransaction = "0xf86b19847735940082520894f6c3fff0b77efe806fcc10176b8cbf71c6dfe3be880429d069189e00008025a0141c8487e4db65457266978a7f8d856b777a51dd9863d31637ccdec8dea74397a07fd0e14d0e3e891882f13acbe68740f1c5bd82a1a254f898cdbec5e9cfa8cf38"
      expectedHash = "0x6ab11d26df13bc3b2cb1c09c4d274bfce325906c617d2bc744b45fa39b7f8c68"
      expectedFrom = "0x42c4f19a097955ff2a013ef8f014977f4e8516c3"
      expectedTo = "0xf6c3fff0b77efe806fcc10176b8cbf71c6dfe3be"
      expectedValue = "300000000000000000"

      txResult = rpc.sendRawTransaction(rawTransaction)
      if not txResult:
         return (False, "Transaction was not accepted")

      # if this test is re-run on a cluster, we'll see a different
      # hash (or an error once nonce tracking works); don't consider
      # it an error for now
      if not txResult == expectedHash:
         log.warn("Receipt hash != expected hash. Was this run on an empty cluster?")

      if not self._productMode:
         logger.warn("No verification done in ethereum mode")
      else:
         tx = request.getTransaction(txResult)
         if not tx:
            return (False, "No transaction receipt found")

         # This is the important one: it tells whether signature address
         # recovery works.
         if not tx["from"] == expectedFrom:
            return (False, "Found from does not match expected from")

         # The rest of these are just checking parsing.
         if not tx["to"] == expectedTo:
            return (False, "Found to does not match expectd to")
         if not tx["value"] == expectedValue:
            return (False, "Found value does not match expected value")

      return (True, None)

   def _test_eth_sendRawContract(self, rpc, request):
      '''
      Check that a raw transaction can create a contract
      '''

      # TODO: lookup current account nonce, and sign transaction with
      # correct nonce. This currently uses a static nonce, and that
      # means the test has to be run against an empty blockchain (or
      # at least once that hasn't seen nonce 1 for this account
      # before).

      # Simple contract that returns 0x42 when called
      rawTransaction = "0xf860010101800197600b80600c6000396000f300604260005260206000f30025a03002710095786aebc788fd00d13aa416e5d9533da8a3d31239d7cd19034c9c8fa04ff6b9c507243f749ed75ea326323f937d9ed52225082d0b4b1c821a016f0c37"
      expectedHash = "0x3e6f541d02217ae7d3cb901c0288c6a5cdeb1ed712428c465d1da934622c995e"
      expectedFrom = "0xf7c93b40e9b8d20af457f49db3bba37a629700d8"
      expectedTo = "0xe832a6e1f1c2442de8073f7df0dda56233c8eccd"
      expectedValue = "1"

      txResult = rpc.sendRawTransaction(rawTransaction)
      if not txResult:
         return (False, "Transaction was not accepted")

      # if this test is re-run on a cluster, we'll see a different
      # hash (or an error once nonce tracking works); don't consider
      # it an error for now
      if not txResult == expectedHash:
         log.warn("Receipt hash != expected hash. Was this run on an empty cluster?")

      if not self._productMode:
         logger.warn("No verification done in ethereum mode")
      else:
         tx = request.getTransaction(txResult)
         if not tx:
            return (False, "No transaction receipt found")

         # This is the important one: it tells whether signature address
         # recovery works.
         if not tx["from"] == expectedFrom:
            return (False, "Found from does not match expected from")

         # The rest of these are just checking parsing.
         if not tx["contract_address"] == expectedTo:
            return (False, "Found contract_address does not match expected contract_address")
         if not tx["value"] == expectedValue:
            return (False, "Found value does not match expected value")

         callResult = rpc.callContract(tx["contract_address"])

         if not callResult == "0x0000000000000000000000000000000000000000000000000000000000000042":
            return (False, "Contract did not return expected value")

      return (True, None)
