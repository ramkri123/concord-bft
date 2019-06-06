import collections
import difflib
import inspect
import json
import logging
import os
import pickle
import pytest
import queue
import sys
import time
from urllib.parse import urlparse
from uuid import UUID

from suites import test_suite
from rest.request import Request
from rpc.rpc_call import RPC
import util.numbers_strings

log = logging.getLogger(__name__)

# The number if blocks/transactions in a page when invoking the
# concord/blocks or concord/transactions calls.
defaultBlocksInAPage = 10
defaultTxInAPage = 10

# The HelenAPITests object.
suiteObject = None

# Holds the results of the _getTests() method.
apiTests = []

# So the pytest fixture will display sensible names.
apiTestNames = []

# These can be generated with https://emn178.github.io/online-tools/keccak_256.html
# hello()
helloFunction = "0x19ff1d21"
# howdy()
howdyFunction = "0x362810fd"

# These can be generated with https://www.rapidtables.com/convert/number/ascii-to-hex.html
# "Hello, World!"
helloHex = "48656c6c6f2c20576f726c6421"
# "Howdy, World!"
howdyHex = "486f7764792c20576f726c6421"

# A user.  This will only work until CSP is implemented and
# concord starts requiring signed transactions.
fromUser = "0x1111111111111111111111111111111111111111"

# The compiler version passed to Helen when uploading contracts.
compilerVersion = "v0.5.2+commit.1df8f40c"

# Automation's contract and version IDs are generated with random_string_generator(),
# which creates strings of six uppercase characters and digits.
nonexistantContractId = "aaaaaaaaaaaaaaaaaaaa"
nonexistantVersionId = "bbbbbbbbbbbbbbbbbbbb"

BaseFixture = collections.namedtuple("BaseFixture", "request, rpc, blockchainId")

# Ideally this would be a fixture.  However, fixtures and
# parametrize decorations don't play well together.  (There
# are threads about it.) So just brute force run this code
# and shove the info into global variables.  :(
with open("pickled_helen_api_tests", "rb") as f:
   suiteObject = pickle.load(f)
   apiTests = suiteObject._getTests()
   for test in apiTests:
      apiTestNames.append(test[0])


@pytest.fixture
def fx(request):
   '''
   This returns a basic fixture containing a Hermes Request object,
   RPC object, and blockchain ID.  The accepted parameter, "request",
   is an internal PyTest name and must be that.
   '''
   longName = os.environ.get('PYTEST_CURRENT_TEST')
   shortName = longName[longName.rindex(":")+1:longName.rindex(" ")]
   testLogDir = os.path.join(suiteObject._testLogDir, shortName)
   request = Request(testLogDir,
                     shortName,
                     suiteObject.reverseProxyApiBaseUrl,
                     suiteObject._userConfig)
   blockchainId = request.getABlockchainId()
   rpc = RPC(request.logDir,
             request.testName,
             getAnEthrpcNode(request, blockchainId)["rpc_url"],
             suiteObject._userConfig)

   return BaseFixture(request=request, rpc=rpc, blockchainId=blockchainId)


def getAnEthrpcNode(request, blockchainId):
   '''
   Return the first ethrpc node for the given blockchain.
   '''
   members = request.getMemberList(blockchainId)

   if members:
      return members[0]
   else:
      raise Exception("getAnEthrpcNode could not get an ethrpc node.")


def ensureEnoughBlocksForPaging(request, blockchainId):
   '''
   Make sure the passed in blockchain has enough blocks to page multiple
   times with the default page size.
   '''
   latestBlockNumber = getLatestBlockNumber(request, blockchainId)
   numBlocks = latestBlockNumber + 1
   minBlocks = defaultBlocksInAPage * 3
   blocksToAdd = minBlocks - numBlocks

   if blocksToAdd > 0:
      log.info("ensureEnoughBocksForPaging is adding {} blocks.".format(blocksToAdd))
      ethrpcNode = getAnEthrpcNode(request, blockchainId)

      for i in range(blocksToAdd):
         suiteObject._mock_transaction(request, data=util.numbers_strings.decToEvenHex(i),
                                       ethrpcNode=ethrpcNode["rpc_url"])

      latestBlockNumber = getLatestBlockNumber(request, blockchainId)
      assert latestBlockNumber + 1 >= minBlocks, "Failed to add enough blocks for paging."


def getLatestBlock(request, blockchainId):
   '''
   Returns the latest block on the given blockchain.
   '''
   blockNumber = getLatestBlockNumber(request, blockchainId)
   return request.getBlockByNumber(blockchainId, blockNumber)


def getLatestBlockNumber(request, blockchainId):
   '''
   Returns the newest block's number for the passed in blockchain.
   '''
   blockList = request.getBlockList(blockchainId)
   return blockList["blocks"][0]["number"]


def addBlocks(request, rpc, blockchainId, numIterations, invokeContracts=False):
   '''
   Adds blocks to the given blockchain.
   numIterations: How many times to loop.  How many blocks you get depends on the value
      of invokeContracts. (n or 2n)
   invokeContracts: If False, only blocks containing contracts will be added (one block
      per iteration). If True, contracts will be added and invoked (two blocks per
      iteration).
   Returns a list of transaction receipts, each with an extra field called "apiCallTime"
   for testing.
   '''
   ethrpcNode = getAnEthrpcNode(request, blockchainId)
   txHashes = []
   txReceipts = []

   for i in range(numIterations):
      contractId, contractVersion = suiteObject.upload_hello_contract(blockchainId, request)
      contractResult = request.callContractAPI('/api/concord/contracts/' + contractId
                                               + '/versions/' + contractVersion, "")
      # Getting the latest block is a workaround for VB-812, "No way to get a transaction
      # receipt for submitting a contract via contract API."
      block = getLatestBlock(request, blockchainId)
      # Time service may have added a block after the contract
      # creation, so look for a block with a transaction in it
      while not block["transactions"]:
         block = request.getBlockByNumber(blockchainId, block["number"]-1)
      txHashes.append(block["transactions"][0]["hash"])

      if invokeContracts:
         txHashes.append(rpc.sendTransaction("0x1111111111111111111111111111111111111111",
                                             helloFunction,
                                             to=contractResult["address"]))

   for txHash in txHashes:
      txReceipt = rpc._getTransactionReceipt(txHash)
      txReceipt["apiCallTime"] = int(time.time())
      txReceipts.append(txReceipt)

   return txReceipts


def addBlocksAndSearchForThem(request, blockchainId, rpc, numBlocks, pageSize):
   '''
   Adds numBlocks blocks and searches for them one pageSize
   of blocks at a time.  This calls a method which handles
   the asserts.
   '''
   origBlockNumber = getLatestBlockNumber(request, blockchainId)
   txResponses = addBlocks(request, rpc, blockchainId, numBlocks)
   newBlockNumber = getLatestBlockNumber(request, blockchainId)
   # If time service is running, there may be additional blocks that
   # were not added by addBlocks
   assert newBlockNumber - origBlockNumber >= numBlocks, \
      "Expected new block to have number {}.".format(origBlockNumber + numBlocks)
   verifyBlocksWithPaging(request, blockchainId, txResponses, pageSize)


def verifyBlocksWithPaging(restRequest, blockchainId, txResponses, pageSize=None):
   '''
   Verifies that the transactions in txResponses are all in blockchain blockchainId
   and the fields are correct.  Searches with paging, instead of fetching the block
   directly.
   '''
   assert txResponses, "No transaction responses passed to verifyBlocks()"

   for txResponse in txResponses:
      blockHash = txResponse["blockHash"]
      blockNumber = int(txResponse["blockNumber"], 16)
      blockUrl = "/api/concord/blocks/" + str(blockNumber)
      block = findBlockWithPaging(restRequest, blockchainId, blockHash,
                                  pageSize)
      assert block, "Block with hash {} not found".format(blockHash)
      assert block["number"] == blockNumber, \
         "Block number should be {}".format(blockNumber)
      assert block["url"] == blockUrl, \
         "Block url should be {}".format(blockUrl)

      # The fields returned when specifying one block are different and
      # are tested via the concord/blocks/{index} tests. Just sanity
      # test here.
      blockFromUrl = restRequest.getBlockByUrl(block["url"])
      assert blockFromUrl["hash"] == blockHash, \
         "Block retrived via URL has an incorrect hash"
      assert blockFromUrl["number"] == blockNumber, \
         "Block retrieved via URL has an incorrect number"
      assert isinstance(blockFromUrl["transactions"], list), \
         "'transactions' field is not a list."
      present, missing = suiteObject.requireFields(blockFromUrl, ["number", "hash",
                                                                  "parentHash", "nonce",
                                                                  "size", "transactions",
                                                                  "timestamp"])
      assert present, "No '{}' field in block response.".format(missing)


def findBlockWithPaging(restRequest, blockchainId, blockHash, pageSize):
   '''
   Given a request, looks for the block with the given blockHash and returns it.
   Instead of asking for a specific block, requests pages of blocks from Helen.
   - pageSize: The number of blocks in a page.
   Returns None if not found.
   '''
   foundBlock = None
   nextUrl = None
   blockList = restRequest.getBlockList(blockchainId, count=pageSize)
   counter = blockList["blocks"][0]["number"]
   lowestBlockNumber = None

   while counter >= 0 and not foundBlock:
      # The last page might not be a full page.  But all other pages should be
      # the expected length.
      if not blockList["blocks"][0]["number"] == 0:
         assert len(blockList["blocks"]) == pageSize, \
            "Returned page of blocks was not the correct size."

         highestInList = blockList["blocks"][0]["number"]
         lowestInList = blockList["blocks"][pageSize-1]["number"]
         assert highestInList - lowestInList == pageSize - 1, \
            "Difference in the block numbers returned do not match the page size."

      for block in blockList["blocks"]:
         counter -= 1

         if block["hash"] == blockHash:
            foundBlock = block
            break

         if lowestBlockNumber == None:
            lowestBlockNumber = block["number"]
         else:
            lowestBlockNumber = min(lowestBlockNumber, block["number"])

      if blockList["next"] and not foundBlock:
         nextUrl = blockList["next"]
         assert nextUrl == "/api/concord/blocks?latest={}".format(lowestBlockNumber-1), \
            "The nextUrl contained the incorrect block number."
         blockList = restRequest.getBlockList(blockchainId, nextUrl=nextUrl, count=pageSize)

   return foundBlock


def checkInvalidIndex(restRequest, blockchainId, index, messageContents):
   '''
   Check that we get an exception containing messageContents when passing
   the given invalid index to concord/blocks/{index}.
   '''
   exceptionThrown = False
   exception = None
   block = None

   try:
      block = restRequest.getBlockByNumber(blockchainId, index)
   except Exception as ex:
      exceptionThrown = True
      exception = ex

   assert exceptionThrown, \
      "Expected an error when requesting a block with invalid index '{}', instead received block {}.".format(index, block)
   assert messageContents in str(exception), \
      "Incorrect error message for invalid index '{}'.".format(index)


def loadGenesisJson():
   '''
   Loads the genesis.json file that is used when launching via Docker.
   Won't work when launching in a real environment.
   '''
   hermes = os.getcwd()
   genFile = os.path.join(hermes, "..", "docker", "config-public", "genesis.json")
   genObject = None

   with open(genFile, "r") as f:
      genObject = json.loads(f.read())

   return genObject


def checkTimestamp(expectedTime, actualTime):
   '''
   Checks that actualTime is near the expectedTime.  The buffer is to
   account for clocks being out of sync on different systems.  We are
   not testing the accuracy of the time; we are testing that the time
   is generally appropriate.
   '''
   buff = 600
   earliestTime = expectedTime - buff
   earliestTimeString = util.numbers_strings.epochToLegible(earliestTime)
   latestTime = expectedTime + buff
   latestTimeString = util.numbers_strings.epochToLegible(latestTime)
   actualTimeString = util.numbers_strings.epochToLegible(actualTime)

   assert actualTime >= earliestTime and actualTime <= latestTime, \
      "Block's timestamp, '{}', was expected to be between '{}' and '{}'. (Converted: " \
      "Block's timestamp, '{}', was expected to be between '{}' and '{}'.)".format(actualTime, \
                                                                earliestTime, latestTime,
                                                                actualTimeString, earliestTimeString,
                                                                latestTimeString)


def verifyContractCreationTx(request, blockchainId, contractCreationTx):
   '''
   Check the fields of a transaction used to create a contract.
   We verify some fields by getting the block the transaction claims to be part of,
   and comparing values with that.
   '''
   # The bytecode changes with Solidity versions.  Just verify the standard first few instructions
   # to verify that the Helen API is working.
   assert contractCreationTx["input"].startswith("0x60806040"), \
      "Input does not appear to be ethereum bytecode."

   block = request.getBlockByNumber(blockchainId, contractCreationTx["block_number"])
   assert block, "Unable to get a block with the transactions block_number."
   assert contractCreationTx["block_hash"] and contractCreationTx["block_hash"] == block["hash"], \
      "The block_hash was not correct."
   assert contractCreationTx["from"] == "0x1111111111111111111111111111111111111111", \
      "The from field was not correct."
   assert contractCreationTx["contract_address"].startswith("0x") and \
      len(contractCreationTx["contract_address"]) == 42, \
      "The value in the contract_address field is not valid"
   # Test with a value?  Maybe use a contract which accepts a value.
   assert contractCreationTx["value"] == "0x0", \
      "The value field is not correct."
   assert isinstance(contractCreationTx["nonce"], int), \
      "Nonce is not an int"
   assert contractCreationTx["hash"] == block["transactions"][0]["hash"], \
      "The hash is not correct."
   assert contractCreationTx["status"] == 1, \
      "The status is not correct."


def verifyContractInvocationTx(request, blockchainId, contractCreationTx,
                               contractInvocationTx):
   '''
   Check the fields of a transaction used to execute a contract.
   We verify some fields by getting the block the transaction claims to be part of.
   We also verify some fields by comparing to fields in transaction which
   created the contract.
   '''
   assert contractInvocationTx["input"] == helloFunction, \
      "Input field was not correct"

   block = request.getBlockByNumber(blockchainId, contractInvocationTx["block_number"])
   assert contractInvocationTx["block_hash"] == block["hash"], \
      "The block_hash field was not correct"
   assert contractInvocationTx["from"] == "0x1111111111111111111111111111111111111111", \
      "The from field was not correct."
   assert contractInvocationTx["to"] == contractCreationTx["contract_address"], \
      "The to field was not equal to the contract's address."
   # Test with a value?  Maybe use a contract which accepts a value.
   assert contractCreationTx["value"] == "0x0", \
      "The value field is not correct."
   assert contractInvocationTx["nonce"] > contractCreationTx["nonce"], \
      "Nonce is not greater than the contract creation transaction's nonce"
   assert contractInvocationTx["hash"] == block["transactions"][0]["hash"], \
      "The hash is not correct."
   assert contractInvocationTx["status"] == 1, \
      "The status is not correct."


def checkBlockchainFields(request, blockchain):
   '''
   Given request and blockchain objects, verify the fields of the blockchain object.
   When this test is being run, we don't know how many concord nodes there will
   really be.  So make sure we get something a) sensible that b) is consistent with
   the another API.
   '''
   expectedRpcUrls = []
   members = request.getMemberList(blockchain["id"])
   for member in members:
      expectedRpcUrls.append(member["rpc_url"])

   # There will always be a genesis block.
   block = request.getBlockByNumber(blockchain["id"], 0)
   assert block["hash"], "Unable to retrive block 0 using the returned blockchain ID."
   UUID(blockchain["consortium_id"])

   # For the case of zero nodes, verify that corner case in its own test case.
   assert blockchain["node_list"], "No nodes were returned in the blockchain."

   for node in blockchain["node_list"]:
      UUID(node["node_id"])
      host, port = node["ip"].split(":")
      assert host.startswith("concord"), "The ip field did not start with 'concord'."
      # This will just raise an exception and fail the test
      int(port)

      urlParts = urlparse(node["url"])
      int(urlParts.port)
      assert urlParts.scheme == "https", "The url field is not using https."
      assert urlParts.hostname, "The url field hostname is empty."
      assert node["url"] in expectedRpcUrls, \
         "The url field contained a value not matching one returned by the /concord/members API."
      expectedRpcUrls.remove(node["url"])

      # TODO: Verify the cert. See VB-747
      assert "region" in node.keys(), "The region field does not exist."

   assert len(expectedRpcUrls) == 0, \
      "More nodes were returned by the /concord/members API than were present in the /blockchains node list."


def validateContractFields(testObj, blockchainId, contractId, contractVersion):
   '''
   testObj: The object to check.
   Other fields: Used as expected values.
   '''
   assert testObj["contract_id"] == contractId, "The contract_id field is not correct."
   assert testObj["version"] == contractVersion, "The contract_version field is not correct."

   # VB-848: The blockchain ID should be in this url, but it is not.
   # assert testObj["url"] == "/api/blockchains/" + blockchainId + "/concord/contracts/" + \
   #    contractId + "/versions/" + contractVersion
   assert len(testObj.keys()) == 3


def verifyContractVersionFields(blockchainId, request, rpc, actualDetails, expectedDetails, expectedVersion,
                                testFunction, testFunctionExpectedResult):
   '''
   Verify the details of a specific contract version.
   expectedVersion varies with every test case, so is determined at run time and passed in.
   actualDetails is a dictionary of all of the other contract values such as the
   abi, devdoc, compiler, etc...
   expectedDetails is loaded from a file and compared to actualDetails.
   testFunction and testFunctionExpectedResult are the hex encoded function to verify the
   address and the expected result.
   '''
   contractCallResult = rpc.callContract(actualDetails["address"], data=testFunction)
   assert testFunctionExpectedResult in contractCallResult, "The test function {} returned an expected result without {}".format(testFunction, testFunctionExpectedResult)

   assert actualDetails["version"] == expectedVersion, \
      "Version was {}, expected {}".format(actualDetails["version"], contractVersion)

   # Do a text diff on the rest of the fields.  Remove the items which always differ
   # first.
   del actualDetails["address"]
   del expectedDetails["address"]
   del actualDetails["version"]
   del expectedDetails["version"]

   result = json.dumps(actualDetails, sort_keys=True, indent=2).split("\n")
   expectedResult = json.dumps(expectedDetails, sort_keys=True, indent=2).split("\n")
   diffs = ""

   for line in difflib.unified_diff(result, expectedResult, lineterm=""):
      diffs += line + "\n"

   assert not diffs, "Differences found in details: {}".format(diffs)


# Runs all of the tests from helen_api_tests.py.
@pytest.mark.smoke
@pytest.mark.oldFormat
@pytest.mark.parametrize("testName", apiTestNames)
def test_oldFormat(testName, fx):
   testLogDir = os.path.join(suiteObject._testLogDir, testName)
   testFn = None
   for apiTest in apiTests:
      if testName in apiTest:
         testFn = apiTest[1]
   assert testFn, "Test named {} not found.".format(testName)
   suiteObject.setEthrpcNode()
   result, info = testFn(fx)
   assert result, info


@pytest.mark.smoke
def test_blockchains_fields(fx):
   blockchains = fx.request.getBlockchains()
   idValid = False
   consortiumIdValid = False

   for b in blockchains:
      blockchainId = UUID(b["id"])
      consortiumId = UUID(b["consortium_id"])


@pytest.mark.smoke
def test_members_fields(fx):
   blockchains = fx.request.getBlockchains()
   result = fx.request.getMemberList(blockchains[0]["id"])

   assert type(result) is list, "Response was not a list"
   assert len(result) >= 1, "No members returned"

   for m in result:
      (present, missing) = suiteObject.requireFields(m, ["hostname", "status", "address",
                                                         "millis_since_last_message",
                                                         "millis_since_last_message_threshold",
                                                         "rpc_url" ])
      assert present, "No '{}' field in member entry.".format(missing)
      assert isinstance(m["hostname"], str), "'hostname' field in member entry is not a string"
      assert isinstance(m["status"], str), "'status' field in member entry is not a string"
      assert isinstance(m["address"], str), "'address' field in member entry is not a string"
      assert isinstance(m["millis_since_last_message"], int), \
         "'millis_since_last_message' field in member entry is not a string"
      assert isinstance(m["millis_since_last_message_threshold"], int), \
         "'millis_since_last_message_threshold field in member entry is not a string"
      assert isinstance(m["rpc_url"], str), "'rpc_url' field in member entry is not a string"
      assert m["rpc_url"] != "", "'rpc_url' field in member entry is empty string"
      assert not "rpc_cert" in m, "'rpc_cert' field should not be included if certs=true is not passed"


@pytest.mark.smoke
def test_members_rpc_url(fx):
   '''
   Test that the returned value for "rpc_url" is an ethrpc node.
   We'll do that by invoking the API. At the moment, Helen still
   supports the API (it is planned to be removed), so also verify
   that we aren't getting Helen's address back by ensuring a
   Helen-only API call fails.
   '''
   result = fx.request.getMemberList(fx.blockchainId)
   ethrpcUrl = None

   for member in result:
      ethrpcUrl = member["rpc_url"]

      # Ensure we have a node that responds to our API.
      # Will throw an exception if not.
      fx.rpc.mining()

      # Ensure that the rpc_url isn't Helen.  This will give a 404
      # and throw an exception.
      invalidRequest = Request(fx.request.logDir,
                               fx.request.testName,
                               ethrpcUrl + "blockchains/local",
                               suiteObject._userConfig)
      try:
         result = invalidRequest.getBlockList(fx.blockchainId)
         assert False, "An exception should have been thrown when asking an ethrpc node for blocks."
      except Exception as e:
         # There are of course various reasons a 404 could be returned.  But let's at least
         # be sure we got back 404 for the given path, indicating this call is not available.
         assert "Not Found" in str(e), "Expected a 404 error about calling 'blocks'."


@pytest.mark.smoke
def test_members_hostname(fx):
   '''
   Verify the "hostname" fields are "replica1", "replica2", ...
   '''
   blockchains = fx.request.getBlockchains()
   result = fx.request.getMemberList(blockchains[0]["id"])
   nodeCount = len(result)
   hostNames = []

   for nodeData in result:
      hostNames.append(nodeData["hostname"])

   for i in range(0, nodeCount):
      findMe = "replica" + str(i)
      assert findMe in hostNames, "Could not find host {} in the response.".format(findMe)
      hostNames.remove(findMe)

   assert len(hostNames) == 0, "Hosts not returned in the response: {}".format(hostNames)


@pytest.mark.smoke
def test_members_millis_since_last_message(fx):
   '''
   Pause a node, get sleep time millis, and make sure it is at least as long as we slept.
   Unpause it, and make sure it decreased.
   The numbers are not exact, but we're not testing concord.  We're just
   testing that Helen is receiving/communicating new values, not always
   showing a default, etc...
   '''
   allMembers = fx.request.getMemberList(fx.blockchainId)
   nodeData = allMembers[0] # Any will do.
   hostName = nodeData["hostname"]
   concordIndex = int(hostName[len("replica"):]) + 1 # replica0 == concord1
   testTime = 0
   sleepTime = 5
   expectedMinimum = sleepTime * 1000

   try:
      suiteObject._resumeMembers(allMembers)

      log.info("Pausing concord{}".format(concordIndex))
      paused = suiteObject.product.pause_concord_replica(str(concordIndex))
      assert paused, "Unable to pause the container.  Hostname: {}, concord #: {}". \
         format(hostName, concordIndex)
      time.sleep(sleepTime)

      result = fx.request.getMemberList(fx.blockchainId)
      for nodeData in result:
         if nodeData["hostname"] == hostName:
            testTime = int(nodeData["millis_since_last_message"])
            break

      assert testTime > expectedMinimum, "Expected millis_since_last_message of " \
         "at least {}, got {}.".format(expectedMinimum, testTime)

      log.info("Resuming concord{}".format(concordIndex))
      resumed = suiteObject.product.resume_concord_replica(str(concordIndex))
      assert resumed, "Unable to resume the container.  Hostname: {}, concord #: {}". \
         format(hostName, concordIndex)

      result = fx.request.getMemberList(fx.blockchainId)
      assert len(result) > 0, "No members returned"

      for nodeData in result:
         if nodeData["hostname"] == hostName:
            testTimeResumed = int(nodeData["millis_since_last_message"])
            assert testTimeResumed < testTime, "Expected millis_since_last_message " \
               "to be less than {}, received {}.".format(reportedSilentTime, finalTime)
   finally:
      suiteObject._resumeMembers(allMembers)


@pytest.mark.smoke
def test_blockList_noNextField_allBlocks(fx):
   '''
   Cause no "next" paging by requesting all blocks.
   '''
   ensureEnoughBlocksForPaging(fx.request, fx.blockchainId)
   latestBlock = getLatestBlockNumber(fx.request, fx.blockchainId)
   # Time service may add blocks in between these requests, so +10
   # ensures that we account for a few rounds of that.
   result = fx.request.getBlockList(fx.blockchainId, count=latestBlock+10)
   assert "next" not in result, \
      "There should not be a 'next' field when requesting all blocks."


@pytest.mark.smoke
def test_blockList_noNextField_firstBlock(fx):
   '''
   Cause no "next" paging by requesting the genesis block.
   '''
   result = fx.request.getBlockList(fx.blockchainId, latest=0)
   assert "next" not in result, \
      "There should not be a 'next' field when latest is 0."


@pytest.mark.smoke
def test_newBlocks_onePage(fx):
   '''
   Add a bunch of blocks and get them all back in a page which is
   larger than the default.
   '''
   addBlocksAndSearchForThem(fx.request, fx.blockchainId, fx.rpc, 11, 11)


@pytest.mark.smoke
def test_newBlocks_spanPages(fx):
   '''
   Add multiple blocks and get them all back via checking many small pages.
   '''
   addBlocksAndSearchForThem(fx.request, fx.blockchainId, fx.rpc, 5, 2)


@pytest.mark.smoke
def test_pageSize_zero(fx):
   ensureEnoughBlocksForPaging(fx.request, fx.blockchainId)
   result = fx.request.getBlockList(fx.blockchainId, count=0)
   assert len(result["blocks"]) == 0, "Expected zero blocks returned."


@pytest.mark.smoke
def test_pageSize_negative(fx):
   ensureEnoughBlocksForPaging(fx.request, fx.blockchainId)
   result = fx.request.getBlockList(fx.blockchainId, count=-1)
   assert len(result["blocks"]) == defaultBlocksInAPage, "Expected {} blocks returned.".format(defaultBlocksInAPage)


@pytest.mark.smoke
def test_pageSize_exceedsBlockCount(fx):
   ensureEnoughBlocksForPaging(fx.request, fx.blockchainId)
   blockCount = getLatestBlockNumber(fx.request, fx.blockchainId) + 1
   result = fx.request.getBlockList(fx.blockchainId, count=blockCount+1)
   assert len(result["blocks"]) == blockCount, "Expected {} blocks returned.".format(blockCount)


@pytest.mark.smoke
def test_paging_latest_negative(fx):
   ensureEnoughBlocksForPaging(fx.request, fx.blockchainId)
   # Reminder that time service might append blocks between these
   # operations, so we can't assert that they all return the same
   # number; only that they're in order
   highestBlockNumberBefore = getLatestBlockNumber(fx.request, fx.blockchainId)
   result = fx.request.getBlockList(fx.blockchainId, latest=-1)
   highestBlockNumberAfter = getLatestBlockNumber(fx.request, fx.blockchainId)
   assert (result["blocks"][0]["number"] >= highestBlockNumberBefore and
           result["blocks"][0]["number"] <= highestBlockNumberAfter), \
           "Expected the latest block to be {}-{}".format(
              highestBlockNumberBefore, highestBlockNumberAfter)


@pytest.mark.smoke
def test_paging_latest_exceedsBlockCount(fx):
   ensureEnoughBlocksForPaging(fx.request, fx.blockchainId)
   # Reminder that time service might append blocks between these
   # operations, so we can't assert that they all return the same
   # number; only that they're in order
   highestBlockNumberBefore = getLatestBlockNumber(fx.request, fx.blockchainId)
   result = fx.request.getBlockList(fx.blockchainId, latest=highestBlockNumberBefore+1)
   highestBlockNumberAfter = getLatestBlockNumber(fx.request, fx.blockchainId)
   assert (result["blocks"][0]["number"] >= highestBlockNumberBefore and
           result["blocks"][0]["number"] <= highestBlockNumberAfter), \
           "Expected the latest block to be {}-{}".format(
              highestBlockNumberBefore, highestBlockNumberAfter)


@pytest.mark.smoke
def test_blockIndex_negative(fx):
   checkInvalidIndex(fx.request, fx.blockchainId, -1, "Invalid block number or hash")


@pytest.mark.smoke
def test_blockIndex_outOfRange(fx):
   latestBlockNumber = getLatestBlockNumber(fx.request, fx.blockchainId)
   # Time service may add blocks between these requests, so +10
   # accounts for a few rounds of that.
   checkInvalidIndex(fx.request, fx.blockchainId, latestBlockNumber+10, "block not found")


# @pytest.mark.smoke
# %5c (backslash) causes HTTP/1.1 401 Unauthorized.  Why?  Is that a bug?
# Filed as VB-800.
# def test_blockIndex_backslash(fx):
#
#    checkInvalidIndex(fx.request, fx.blockchainId, "%5c", "Invalid block number or hash")


@pytest.mark.smoke
def test_blockIndex_atSymbol(fx):
   checkInvalidIndex(fx.request, fx.blockchainId, "%40", "Invalid block number or hash")


@pytest.mark.smoke
def test_blockIndex_word(fx):
   checkInvalidIndex(fx.request, fx.blockchainId, "elbow", "Invalid block number or hash")


@pytest.mark.smoke
def test_blockIndex_zero(fx):
   '''
   This test case will only work when running locally, such as a dev/
   CI/CD environment.  It's being run because block 0 is a special case:
   - Block 0 is the only block (today anyway) in the VMware Blockchain
     system which can contain multiple transactions, and we want to test
     that.
   - Block 0's timestamp is not created the same way as the others.
   - Block 0 is created from genesis.json.  e.g. Some accounts are
     preloaded with ether.
   '''
   genObject = loadGenesisJson()
   block = fx.request.getBlockByNumber(fx.blockchainId, 0)
   foundAccounts = []
   expectedNonce = 0

   assert block["number"] == 0, "Block 0's number was not 0"
   assert block["size"] == 1, "Block 0's size was '{}', expected 1".format(block["size"])
   assert int(block["parentHash"], 16) == 0, \
      "Block 0's parent hash was '{}', expected 0".format(block["parentHash"])
   assert int(block["nonce"], 16) == 0, \
      "Block 0's nonce was '{}', expected 0".format(block["nonce"])
   # VB-801: Block 0's timestamp is 0.
   # assert block["timestamp"] == ...

   assert len(block["transactions"]) == len(genObject["alloc"]), \
      "The number of transactions in block 0 does not match the number in genesis.json."

   for tx in block["transactions"]:
      expectedUrl = "/api/concord/transactions/" + tx["hash"]
      assert tx["url"] == expectedUrl, \
         "Transaction url in block 0 was {}, expected {}".format(tx["url"], expectedUrl)

      # Look up the transaction in block 0 and save its recipient.  Later, we will
      # verify that the accounts in genesis.json which have pre-allocated accounts
      # match the recipients in the transactions in block 0.
      fullTx = fx.request.getTransaction(fx.blockchainId, tx["hash"])
      foundAccounts.append(util.numbers_strings.trimHexIndicator(fullTx["to"]))

      assert fullTx["block_number"] == 0, \
         "Transaction in block 0 is listed as being in {}".format(fullTx["block_number"])
      assert int(fullTx["from"], 16) == 0, \
         "Expected initial alloc of ether to be from account 0"
      assert fullTx["hash"] == tx["hash"], \
         "Hash field in transaction {} was {}".format(tx["hash"], fullTx["hash"])
      assert fullTx["status"] == 1, "Expected a status of 1"

      # Genesis.json specifies the opening balance in hex and dec.
      expectedBalance = genObject["alloc"][util.numbers_strings.trimHexIndicator(fullTx["to"])]["balance"]
      if expectedBalance.startswith("0x"):
         expectedBalance = int(expectedBalance, 16)
      else:
         expectedBalance = int(expectedBalance)
      assert expectedBalance == int(fullTx["value"], 16), \
         "Expected balance to be '{}', was '{}'".format(expectedBalance, fullTx["value"])

      assert fullTx["nonce"] == expectedNonce, \
         "Expected block 0 transaction to have nonce '{}'".format(expectedNonce)
      expectedNonce += 1

   expectedAccounts = list(genObject["alloc"].keys())
   assert sorted(expectedAccounts) == sorted(foundAccounts), \
      "In block 0, expected accounts {} not equal to found accounts {}".format(expectedAccounts, foundAccounts)


@pytest.mark.smoke
def test_blockIndex_basic(fx):
   '''
   Add a few blocks, fetch them by block index, and check the fields.
   Getting a block by index returns:
   {
     "number": 0,
     "hash": "string",
     "parentHash": "string",
     "nonce": "string",
     "size": 0,
     "timestamp": 0,
     "transactions": [
       "string"
     ]
   }
   '''
   numBlocks = 3
   txResponses = addBlocks(fx.request, fx.rpc, fx.blockchainId, numBlocks)
   parentHash = None

   for txResponse in txResponses:
      block = fx.request.getBlockByNumber(fx.blockchainId, int(txResponse["blockNumber"], 16))
      assert block["number"] == int(txResponse["blockNumber"], 16), "Number is not correct."
      assert block["hash"] == txResponse["blockHash"], "Hash is not correct."

      if parentHash:
         # Time service may insert blocks between these transactions,
         # so we're going to verify that a previous transaction's
         # block is somewhere in this block's lineage, instead of
         # being this block's direct parent
         searchBlock = block
         while searchBlock["parentHash"] != parentHash:
            assert searchBlock["number"] > 0, "Block with parent hash not found"
            if searchBlock["number"] > 0:
               searchBlock = fx.request.getBlockByNumber(fx.blockchainId, searchBlock["number"]-1)

      # This block's hash is the parentHash of the next one.
      parentHash = block["hash"]

      # The block nonce is not used, but it is required for compliance.
      assert int(block["nonce"], 16) == 0

      # Block size is always 1 right now.  Investigate.  Not a Helen issue though.
      # Internet searches say Ethereum block size has to do with gas, not the
      # size of something in bytes.
      assert block["size"] == 1

      checkTimestamp(txResponse["apiCallTime"], block["timestamp"])

      # We have one transaction per block, except block 0, which is handled
      # in a different use case.
      assert len(block["transactions"]) == 1, \
         "Expected one transaction per block (except block zero)"
      assert txResponse["transactionHash"] == block["transactions"][0]["hash"], \
         "The block's transaction hash does not match the transaction hash " \
         "given when the block was added."


@pytest.mark.smoke
def test_transactionHash_basic(fx):
   '''
   Add a contract, invoke it, and check that the two transactions added can be
   retrieved as well as contain appropriate values.
   '''
   txReceipts = addBlocks(fx.request, fx.rpc, fx.blockchainId, 1, True)
   contractCreationTxHash = txReceipts[0]["transactionHash"]
   contractInvocationTxHash = txReceipts[1]["transactionHash"]
   contractCreationTx = fx.request.getTransaction(fx.blockchainId, contractCreationTxHash)
   contractInvocationTx = fx.request.getTransaction(fx.blockchainId, contractInvocationTxHash)

   # VB-814: The transaction_index field is missing.

   verifyContractCreationTx(fx.request, fx.blockchainId, contractCreationTx)
   verifyContractInvocationTx(fx.request, fx.blockchainId, contractCreationTx,
                              contractInvocationTx)


@pytest.mark.smoke
def test_transactionHash_invalid_zero(fx):
   '''
   Submit an invalid value for the transaction.
   '''
   invalidTx = fx.request.getTransaction(fx.blockchainId, "0")
   assert len(invalidTx) == 0, "Invalid transaction ID should return an empty set."


@pytest.mark.smoke
def test_transactionHash_invalid_negOne(fx):
   '''
   Submit an invalid value for the transaction.
   '''
   invalidTx = fx.request.getTransaction(fx.blockchainId, "-1")
   assert len(invalidTx) == 0, "Invalid transaction ID should return an empty set."


@pytest.mark.smoke
def test_transactionHash_invalid_tooLong(fx):
   '''
   Submit an invalid value for the transaction.
   '''
   invalidTx = fx.request.getTransaction(fx.blockchainId, "0xc5555c44eabcc1fcf93ca1b69bcc2a56a4960bc1380fcbb2121eca5ba6aa6f41a")
   assert len(invalidTx) == 0, "Invalid transaction ID should return an empty set."


@pytest.mark.smoke
def test_blockchains_one(fx):
   '''
   Test with one blockchain deployed, which is the default.
   '''
   blockchains = fx.request.getBlockchains()
   assert len(blockchains) == 1, "Expected one blockchain to be returned"
   blockchain = blockchains[0]
   checkBlockchainFields(fx.request, blockchain)


@pytest.mark.smoke
def test_getABlockchain_valid(fx):
   '''
   Test GET /blockchains/{bid}, which gets details for a given blockchain.
   '''
   blockchainId = fx.request.getBlockchains()[0]["id"]
   details = fx.request.getBlockchainDetails(blockchainId)
   checkBlockchainFields(fx.request, details)


@pytest.mark.smoke
def test_getABlockhain_invalid_uuid(fx):
   '''
   Test GET /blockchains/{bid} with a UUID that is already used.
   This test's UUID was generated by Helen once, so the test *should*
   pass for a billion or more years.  Willing to accept the risk.
   '''
   blockchainId = "8fecf880-26e3-4d71-9778-ad1592324684"
   details = fx.request.getBlockchainDetails(blockchainId)
   log.info(details)
   assert details["error_code"] == "NotFoundException", \
      "Expected NotFoundException, received {}".format(details["error_code"])

   # VB-951: Let's not talk about the column name.  I'm not skipping the test for that.
   #
   # errorMsg = "Entity is not found for Id 8fecf880-26e3-4d71-9778-ad1592324684, column name helen.blockchain."
   # assert details["error_message"] == errorMsg, \
   #    "Expected {}, received {}".format(errorMsg, details["error_message"])
   errorFragment = "Entity is not found for Id 8fecf880-26e3-4d71-9778-ad1592324684"
   assert errorFragment in details["error_message"], \
      "Expected {} to be in {}".format(errorFragment, details["error_message"])

   assert details["status"] == 404, "Expected status 404, received {}".format(detail["status"])

   path = "/api/blockchains/" + blockchainId
   assert details["path"] == path, \
      "Expected {}, received {}".format(path, details["path"])


@pytest.mark.smoke
def test_getABlockhain_invalid_uuid_format(fx):
   '''
   Test GET /blockchains/{bid} with an invalid uuid format.
   Not sure if this should be as it is now or be "entity not found" like above.
   Verification just matches product behavior for now.
   '''
   blockchainId = "aa"
   details = fx.request.getBlockchainDetails(blockchainId)

   ex = "MethodArgumentTypeMismatchException"
   assert details["error_code"] == ex, \
      "Expected {}, received {}".format(ex, details["error_code"])

   msg = "Failed to convert value of type 'java.lang.String' to required type " \
         "'java.util.UUID'; nested exception is java.lang.IllegalArgumentException: " \
         "Invalid UUID string: aa"
   assert details["error_message"] == msg, \
      "Expected {}, received {}".format(msg, details["error_message"])

   assert details["status"] == 500, \
      "Expected {}, received {}".format(500, details["status"])

   path = "/api/blockchains/aa"
   assert details["path"] == path, \
      "Expected {}, received {}".format(path, details["path"])


@pytest.mark.skip(reason="Not implemented")
def test_blockchains_none(fx):
   '''
   How to start the product with no blockchains?
   Filed VB-841: Not able to start Helen with no blockchains.
   '''
   # restartTheProductWithNoBlockchains()
   # blockchains = fx.request.getBlockchains()
   # assert len(blockchains) == 0, "Expected zero blockchains to be returned"
   pass


@pytest.mark.skip(reason="Not implemented")
def test_blockchains_multiple(fx):
   '''
   Currently, there is no way to deploy multiple blockchains.
   '''
   # addAnotherBlockchain()
   # blockchains = fx.request.getBlockchains()
   # assert len(blockchains) == 2, "Expected zero blockchains to be returned"
   # beSureTheBlockchainsAreDifferentAndUsingTheCorrectConcordNodes()
   # checkBlockchainFields(fx.request, blockchain)
   pass


@pytest.mark.smoke
def test_getContracts(fx):
   '''
   Verify:
   Post several contracts and be sure we can retrieve them.
   '''
   beforeContractList = fx.request.getContracts(fx.blockchainId)
   numNew = 3
   newContractResults = []

   for _ in range(numNew):
      newContractResults.append(suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                            "resources/contracts/HelloWorld.sol",
                                                            "HelloWorld",
                                                            fromAddr=fromUser,
                                                            compilerVersion=compilerVersion))
   afterContractList = fx.request.getContracts(fx.blockchainId)
   assert len(beforeContractList) + numNew == len(afterContractList), \
      "Unexpected new number of contracts."

   # We could use the contract id/version api to check, but since it's easy to avoid
   # using the API we're testing in this case, let's not.
   for newContract in newContractResults:
      found = False

      for contract in afterContractList:
         if contract["contract_id"] == newContract["contract_id"] and \
            contract["owner"] == fromUser and \
            newContract["url"].startswith(contract["url"]):
            found = True
            break

      assert found, "Newly added contract not found"


@pytest.mark.smoke
def test_postContract_simple(fx):
   '''
   Post a basic contract, check the result values, and run it.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorld.sol",
                                                "HelloWorld",
                                                fromAddr=fromUser,
                                                compilerVersion=compilerVersion,
                                                contractId=contractId,
                                                contractVersion=contractVersion)
   validateContractFields(contractResult, fx.blockchainId, contractId, contractVersion)
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)

   result = fx.rpc.callContract(contract["address"], data=helloFunction)
   assert helloHex in result, "Simple uploaded contract not executed correctly."


@pytest.mark.smoke
#@pytest.mark.skip(reason="VB-857")
def test_postContract_constructor(fx):
   '''
   Post a contract with a constructor and run it.
   The constructor data must be even length hex string, no 0x prefix.
   (It gets appended to the bytecode.)
   '''

   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   constructorParam = util.numbers_strings.decToInt256HexNo0x(10)
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/CounterWithConstructorParam.sol",
                                                "Counter",
                                                fromAddr=fromUser,
                                                compilerVersion=compilerVersion,
                                                contractId=contractId,
                                                contractVersion=contractVersion,
                                                ctorParams=constructorParam)
   validateContractFields(contractResult, fx.blockchainId, contractId, contractVersion)
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)

   callContractResult = fx.rpc.callContract(contract["address"], data="0xa87d942c")
   assert int(callContractResult, 16) == 10, "Constructor value was not used."


@pytest.mark.smoke
def test_postContract_optimized(fx):
   '''
   Post a contract that is optimized.  Prove that Helen used the optimize
   flag by comparing to bytecode which is not optimized.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/CounterWithConstructorParam.sol",
                                                "Counter",
                                                fromAddr=fromUser,
                                                compilerVersion=compilerVersion,
                                                contractId=contractId,
                                                contractVersion=contractVersion,
                                                optimize = False)
   validateContractFields(contractResult, fx.blockchainId, contractId, contractVersion)
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)
   unoptimizedBytecode = contract["bytecode"]

   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/CounterWithConstructorParam.sol",
                                                "Counter",
                                                fromAddr=fromUser,
                                                compilerVersion=compilerVersion,
                                                contractId=contractId,
                                                contractVersion=contractVersion,
                                                optimize=True,
                                                runs="1")
   validateContractFields(contractResult, fx.blockchainId, contractId, contractVersion)
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)
   optimizedBytecode1Run = contract["bytecode"]
   assert optimizedBytecode1Run != unoptimizedBytecode, "Bytecode was not optimized"


@pytest.mark.smoke
def test_postContract_optimizeRuns(fx):
   '''
   Optimize the contract for different run frequencies. Prove
   that Helen used the run parameter by comparing bytecode.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorld.sol",
                                                "HelloWorld",
                                                fromAddr=fromUser,
                                                compilerVersion=compilerVersion,
                                                contractId=contractId,
                                                contractVersion=contractVersion,
                                                optimize=True,
                                                runs="1")
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)
   optimizedBytecode1Run = contract["bytecode"]

   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorld.sol",
                                                "HelloWorld",
                                                fromAddr=fromUser,
                                                compilerVersion=compilerVersion,
                                                contractId=contractId,
                                                contractVersion=contractVersion,
                                                optimize=True,
                                                runs="200")
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)
   optimizedBytecode200Runs = contract["bytecode"]

   assert optimizedBytecode200Runs != optimizedBytecode1Run, \
      "Change in runs did not produce different optimized bytecode."


@pytest.mark.smoke
def test_postContract_multiple_first(fx):
   '''
   Submit a file with multiple contracts, specifying the first as the contract.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorldMultiple.sol",
                                                "HelloWorld",
                                                fromAddr=fromUser,
                                                compilerVersion=compilerVersion,
                                                contractId=contractId,
                                                contractVersion=contractVersion)
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)
   assert helloHex in contract["bytecode"], "HelloWorld! is not present in bytecode."
   assert howdyHex not in contract["bytecode"], "HowdyWorld! should not be in the bytecode."


@pytest.mark.smoke
def test_postContract_multiple_second(fx):
   '''
   Submit a file with multiple contracts, specifying the second as the contract.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorldMultiple.sol",
                                                "HowdyWorld",
                                                fromAddr=fromUser,
                                                compilerVersion=compilerVersion,
                                                contractId=contractId,
                                                contractVersion=contractVersion)
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)
   assert howdyHex in contract["bytecode"], "HowdyWorld! is not present in the bytecode."
   assert helloHex not in contract["bytecode"], "HelloWorld! should not be in the bytecode."


@pytest.mark.skip(reason="VB-850")
def test_postContract_noContractId(fx):
   '''
   Try to submit a contract without an ID.
   '''
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorld.sol",
                                                "HelloWorld",
                                                contractId=None,
                                                contractVersion=contractVersion,
                                                fromAddr=fromUser,
                                                compilerVersion=compilerVersion,
                                                ctorParams="",
                                                optimize=False,
                                                generateDefaults=False)


@pytest.mark.skip(reason="VB-850")
def test_postContract_noContractVersion(fx):
   '''
   Try to submit a contract without a version.
   '''
   contractId = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorld.sol",
                                                "HelloWorld",
                                                contractId=contractId,
                                                contractVersion=None,
                                                fromAddr=fromUser,
                                                compilerVersion=compilerVersion,
                                                ctorParams="",
                                                optimize=False,
                                                generateDefaults=False)


@pytest.mark.skip(reason="VB-851")
def test_postContract_noContractFrom(fx):
   '''
   Try to submit a contract without a "from".
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorld.sol",
                                                "HelloWorld",
                                                contractId=contractId,
                                                contractVersion=contractVersion,
                                                fromAddr=None,
                                                compilerVersion=compilerVersion,
                                                ctorParams="",
                                                optimize=False,
                                                generateDefaults=False)
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)


@pytest.mark.skip(reason="VB-847")
def test_postContract_noContractSource(fx):
   '''
   Try to submit a contract without source code.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                None,
                                                "HelloWorld",
                                                contractId=contractId,
                                                contractVersion=contractVersion,
                                                fromAddr=fromUser,
                                                compilerVersion=compilerVersion,
                                                ctorParams="",
                                                optimize=False,
                                                generateDefaults=False)
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)


@pytest.mark.skip(reason="VB-854")
def test_postContract_noContractName(fx):
   '''
   Try to submit a contract without a name.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorld.sol",
                                                None,
                                                contractId=contractId,
                                                contractVersion=contractVersion,
                                                fromAddr=fromUser,
                                                compilerVersion=compilerVersion,
                                                ctorParams="",
                                                optimize=False,
                                                generateDefaults=False)
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)


@pytest.mark.skip(reason="VB-853")
def test_postContract_noContractConstructor(fx):
   '''
   Try to submit a contract without constructor parameters when the
   constructor requires one.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorld.sol",
                                                "HelloWorld",
                                                contractId=contractId,
                                                contractVersion=contractVersion,
                                                fromAddr=fromUser,
                                                compilerVersion=compilerVersion,
                                                ctorParams=None,
                                                optimize=False,
                                                generateDefaults=False)
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)
   result = fx.rpc.callContract(contract["address"], data=helloFunction)
   assert helloHex in result, "Simple uploaded contract not executed correctly."


@pytest.mark.skip(reason="VB-854")
def test_postContract_noContractCompilerVersion(fx):
   '''
   Try to submit a contract without a compiler version.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorld.sol",
                                                "HelloWorld",
                                                contractId=contractId,
                                                contractVersion=contractVersion,
                                                fromAddr=fromUser,
                                                compilerVersion=None,
                                                ctorParams="",
                                                optimize=False,
                                                generateDefaults=False)
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)


@pytest.mark.smoke
def test_postContract_duplicateContractAndVersion(fx):
   '''
   Try to submit a contract with an id/version matching one that exists.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorld.sol",
                                                "HelloWorld",
                                                contractId=contractId,
                                                contractVersion=contractVersion)
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)
   assert helloHex in contract["bytecode"], "HelloWorld! should be in the bytecode."

   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorld.sol",
                                                "HelloWorld",
                                                contractId=contractId,
                                                contractVersion=contractVersion)
   assert contractResult["error_code"] == "ConflictException", \
      "Expected a 'ConflictException' error code, got '{}'".format(contractResult["error_code"])
   expectedMessage = "ContractVersion with id {} and version {} already exists".format(contractId, contractVersion)
   assert contractResult["error_message"] == expectedMessage, \
      "Expected error message '{}', got '{}'".format(expectedMessage, contractResult["error_message"])


@pytest.mark.smoke
def test_postContract_duplicateContractNewVersion(fx):
   '''
   Try to submit a contract with the same ID and a new version.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorldMultiple.sol",
                                                "HelloWorld",
                                                contractId=contractId,
                                                contractVersion=contractVersion)

   contractVersion = suiteObject.random_string_generator(mustNotMatch=contractVersion)
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorldMultiple.sol",
                                                "HowdyWorld",
                                                contractId=contractId,
                                                contractVersion=contractVersion)
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)
   assert howdyHex in contract["bytecode"], "HowdyWorld! should be in the bytecode."
   assert helloHex not in contract["bytecode"], "HelloWorld! should not be in the bytecode."


@pytest.mark.smoke
def test_postContract_newContractDuplicateVersion(fx):
   '''
   Submit a contract with a new ID and the same version.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorldMultiple.sol",
                                                "HelloWorld",
                                                contractId=contractId,
                                                contractVersion=contractVersion)

   contractId = suiteObject.random_string_generator(mustNotMatch=contractId)
   contractResult = suiteObject.upload_contract(fx.blockchainId, fx.request,
                                                "resources/contracts/HelloWorldMultiple.sol",
                                                "HowdyWorld",
                                                contractId=contractId,
                                                contractVersion=contractVersion)
   contract = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)
   assert howdyHex in contract["bytecode"], "HowdyWorld! should be in the bytecode."
   assert helloHex not in contract["bytecode"], "HelloWorld! should not be in the bytecode."


@pytest.mark.smoke
def test_getContractById_idInvalid(fx):
   '''
   Try to get a contract by ID when the ID is invalid.
   '''
   result = fx.request.getAllContractVersions(fx.blockchainId, nonexistantContractId)
   expectedPath = "/api/blockchains/{}/concord/contracts/{}".format(fx.blockchainId, nonexistantContractId)

   assert result["error_code"] == "NotFoundException", \
      "Error code was {}, expected {}".format(result["error_code"], code)

   assert result["error_message"] == "Contract not found: {}".format(nonexistantContractId), \
      "Error message was {}, expected {}".format(result["error_message"], message)

   assert result["status"] == 404, \
      "Status was {}, expected {}".format(result["status"], status)

   assert result["path"] == expectedPath, \
      "Path was {}, expected {}".format(result["path"], expectedPath)


@pytest.mark.smoke
def test_getContractById_oneVersion(fx):
   '''
   Upload one version of a contract, get it with /contracts/{id}, and
   verify that it is correct.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   suiteObject.upload_contract(fx.blockchainId, fx.request,
                               "resources/contracts/HelloWorldMultipleWithDoc.sol",
                               "HelloWorld",
                               contractId=contractId,
                               contractVersion=contractVersion)
   helloExpectedDetails = util.json_helper.readJsonFile("resources/contracts/HelloWorldMultiple_helloExpectedData.json")
   result = fx.request.getAllContractVersions(fx.blockchainId, contractId)

   # These are properties of the contract, not versions.
   assert result["contract_id"] == contractId, \
      "Contract ID was {}, expected {}".format(result["contract_id"], contractId)

   assert result["owner"] == fromUser, \
      "Contract user was {}, expected {}".format(result["owner"], fromUser)

   assert len(result["versions"]) == 1, \
      "Contract should have had one version, actually had {}".format(len(result["versions"]))

   # The data returned from this call doesn't include the bytecode or sourcecode fields.
   assert not hasattr(result["versions"][0], "bytecode"), \
      "Did not expect to find the bytecode field."
   assert not hasattr(result["versions"][0], "sourcecode"), \
      "Did not expect to find the sourcecode field."
   del helloExpectedDetails["bytecode"]
   del helloExpectedDetails["sourcecode"]

   verifyContractVersionFields(fx.blockchainId,
                               fx.request,
                               fx.rpc,
                               result["versions"][0],
                               helloExpectedDetails,
                               contractVersion,
                               helloFunction,
                               helloHex)


@pytest.mark.smoke
def test_getContractById_multipleVersions(fx):
   '''
   Upload multiple versions of a contract, get all of them in one call with /contracts/{id},
   and verify that they are correct.
   '''
   contractId = suiteObject.random_string_generator()
   helloVersion = suiteObject.random_string_generator()

   # Send Hello.
   suiteObject.upload_contract(fx.blockchainId, fx.request,
                               "resources/contracts/HelloWorldMultipleWithDoc.sol",
                               "HelloWorld",
                               contractId=contractId,
                               contractVersion=helloVersion,
                               optimize=False)
   helloExpectedDetails = util.json_helper.readJsonFile\
                          ("resources/contracts/HelloWorldMultiple_helloExpectedData.json")

   # Send Howdy as a new version of the same contract.
   howdyVersion = suiteObject.random_string_generator(mustNotMatch=helloVersion)
   suiteObject.upload_contract(fx.blockchainId, fx.request,
                               "resources/contracts/HelloWorldMultipleWithDoc.sol",
                               "HowdyWorld",
                               contractId=contractId,
                               contractVersion=howdyVersion,
                               optimize=True,
                               runs=100)
   howdyExpectedDetails = util.json_helper.readJsonFile\
                          ("resources/contracts/HelloWorldMultiple_howdyExpectedData.json")
   result = fx.request.getAllContractVersions(fx.blockchainId, contractId)

   # These are properties of the contract.
   assert result["contract_id"] == contractId, \
      "Contract ID was {}, expected {}".format(result["contract_id"], contractId)

   assert result["owner"] == fromUser, \
      "Contract user was {}, expected {}".format(result["owner"], fromUser)

   assert len(result["versions"]) == 2, \
      "Contract should have had two versions, actually had {}".format(len(result["versions"]))

   # The data returned from this call doesn't include the bytecode or sourcecode fields.
   assert not hasattr(result["versions"][0], "bytecode"), \
      "Did not expect to find the bytecode field."
   assert not hasattr(result["versions"][0], "sourcecode"), \
      "Did not expect to find the sourcecode field."
   del helloExpectedDetails["bytecode"]
   del helloExpectedDetails["sourcecode"]
   verifyContractVersionFields(fx.blockchainId,
                               fx.request,
                               fx.rpc,
                               result["versions"][0],
                               helloExpectedDetails,
                               helloVersion,
                               helloFunction,
                               helloHex)

   assert not hasattr(result["versions"][1], "bytecode"), \
      "Did not expect to find the bytecode field."
   assert not hasattr(result["versions"][1], "sourcecode"), \
      "Did not expect to find the sourcecode field."
   del howdyExpectedDetails["bytecode"]
   del howdyExpectedDetails["sourcecode"]
   verifyContractVersionFields(fx.blockchainId,
                               fx.request,
                               fx.rpc,
                               result["versions"][1],
                               howdyExpectedDetails,
                               howdyVersion,
                               howdyFunction,
                               howdyHex)

@pytest.mark.smoke
def test_getContractVersionById_oneVersion(fx):
   '''
   Upload one version of a contract, fetch it with /contract/{id}/version/{id}, and
   verify that the fields are correct.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   suiteObject.upload_contract(fx.blockchainId, fx.request,
                               "resources/contracts/HelloWorldMultipleWithDoc.sol",
                               "HelloWorld",
                               contractId=contractId,
                               contractVersion=contractVersion,
                               optimize=False)
   result = fx.request.getContractVersion(fx.blockchainId, contractId, contractVersion)

   # These are in the contract part of the structure when fetching all versions of a contract.
   # When fetching version info, they are included with the version.
   assert result["contract_id"] == contractId, \
      "Contract ID was {}, expected {}".format(result["contract_id"], contractId)
   del result["contract_id"]

   assert result["owner"] == fromUser, \
      "Contract user was {}, expected {}".format(result["owner"], fromUser)
   del result["owner"]

   helloExpectedDetails = util.json_helper.readJsonFile("resources/contracts/HelloWorldMultiple_helloExpectedData.json")

   verifyContractVersionFields(fx.blockchainId,
                               fx.request,
                               fx.rpc,
                               result,
                               helloExpectedDetails,
                               contractVersion,
                               helloFunction,
                               helloHex)


@pytest.mark.smoke
def test_getContractVersionById_firstVersion(fx):
   '''
   Upload multiple versions of a contract, fetch the first with /contract/{id}/version/{id}, and
   verify that the fields are correct.
   '''
   contractId = suiteObject.random_string_generator()
   helloVersion = suiteObject.random_string_generator()
   howdyVersion = suiteObject.random_string_generator(mustNotMatch=helloVersion)
   suiteObject.upload_contract(fx.blockchainId, fx.request,
                               "resources/contracts/HelloWorldMultipleWithDoc.sol",
                               "HelloWorld",
                               contractId=contractId,
                               contractVersion=helloVersion,
                               optimize=False)
   suiteObject.upload_contract(fx.blockchainId, fx.request,
                               "resources/contracts/HelloWorldMultipleWithDoc.sol",
                               "HelloWorld",
                               contractId=contractId,
                               contractVersion=howdyVersion,
                               optimize=False)
   result = fx.request.getContractVersion(fx.blockchainId, contractId, helloVersion)

   # These are in the contract part of the structure when fetching all versions of a contract.
   # When fetching version info, they are included with the version.
   assert result["contract_id"] == contractId, \
      "Contract ID was {}, expected {}".format(result["contract_id"], contractId)
   del result["contract_id"]

   assert result["owner"] == fromUser, \
      "Contract user was {}, expected {}".format(result["owner"], fromUser)
   del result["owner"]

   helloExpectedDetails = util.json_helper.readJsonFile("resources/contracts/HelloWorldMultiple_helloExpectedData.json")

   verifyContractVersionFields(fx.blockchainId,
                               fx.request,
                               fx.rpc,
                               result,
                               helloExpectedDetails,
                               helloVersion,
                               helloFunction,
                               helloHex)


@pytest.mark.smoke
def test_getContractVersionById_lastVersion(fx):
   '''
   Upload multiple versions of a contract, fetch the last with /contract/{id}/version/{id}, and
   verify that the fields are correct.
   '''
   contractId = suiteObject.random_string_generator()
   helloVersion = suiteObject.random_string_generator()
   howdyVersion = suiteObject.random_string_generator(mustNotMatch=helloVersion)
   suiteObject.upload_contract(fx.blockchainId, fx.request,
                               "resources/contracts/HelloWorldMultipleWithDoc.sol",
                               "HelloWorld",
                               contractId=contractId,
                               contractVersion=helloVersion,
                               optimize=False)
   suiteObject.upload_contract(fx.blockchainId, fx.request,
                               "resources/contracts/HelloWorldMultipleWithDoc.sol",
                               "HowdyWorld",
                               contractId=contractId,
                               contractVersion=howdyVersion,
                               optimize=True,
                               runs=100)
   result = fx.request.getContractVersion(fx.blockchainId, contractId, howdyVersion)

   # These are in the contract part of the structure when fetching all versions of a contract.
   # When fetching version info, they are included with the version.
   assert result["contract_id"] == contractId, \
      "Contract ID was {}, expected {}".format(result["contract_id"], contractId)
   del result["contract_id"]

   assert result["owner"] == fromUser, \
      "Contract user was {}, expected {}".format(result["owner"], fromUser)
   del result["owner"]

   howdyExpectedDetails = util.json_helper.readJsonFile("resources/contracts/HelloWorldMultiple_howdyExpectedData.json")

   verifyContractVersionFields(fx.blockchainId,
                               fx.request,
                               fx.rpc,
                               result,
                               howdyExpectedDetails,
                               howdyVersion,
                               howdyFunction,
                               howdyHex)


@pytest.mark.smoke
def test_getContractVersionById_invalidVersion(fx):
   '''
   Pass an invalid version to /contracts/{id}/versions/{id}.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   suiteObject.upload_contract(fx.blockchainId, fx.request,
                               "resources/contracts/HelloWorldMultipleWithDoc.sol",
                               "HelloWorld",
                               contractId=contractId,
                               contractVersion=contractVersion,
                               optimize=False)
   result = fx.request.getContractVersion(fx.blockchainId, contractId, nonexistantVersionId)

   assert result["error_code"] == "NotFoundException", \
      "Expected a 'NotFoundException' error code, got '{}'".format(result["error_code"])
   expectedMessage = "Contract version not found  {}:{}".format(contractId, nonexistantVersionId)
   assert result["error_message"] == expectedMessage, \
      "Expected error message '{}', got '{}'".format(expectedMessage, result["error_message"])
   assert result["status"] == 404, \
      "Expected status 404, received {}".format(result["status"])
   expectedPath = "/api/blockchains/{}/concord/contracts/{}/versions/{}". \
                  format(fx.blockchainId, contractId, nonexistantVersionId)
   assert result["path"] == expectedPath, \
      "Expected path {}, received {}".format(expectedPath, result["path"])


@pytest.mark.smoke
def test_getContractVersionById_invalidContract(fx):
   '''
   Pass an invalid contract to /contracts/{id}/versions/{id}.
   '''
   contractId = suiteObject.random_string_generator()
   contractVersion = suiteObject.random_string_generator()
   suiteObject.upload_contract(fx.blockchainId, fx.request,
                               "resources/contracts/HelloWorldMultipleWithDoc.sol",
                               "HelloWorld",
                               contractId=contractId,
                               contractVersion=contractVersion,
                               optimize=False)
   result = fx.request.getContractVersion(fx.blockchainId, nonexistantContractId, contractVersion)
   assert result["error_code"] == "NotFoundException", \
      "Expected a 'NotFoundException' error code, got '{}'".format(result["error_code"])
   expectedMessage = "Contract version not found  {}:{}".format(nonexistantContractId, contractVersion)
   assert result["error_message"] == expectedMessage, \
      "Expected error message '{}', got '{}'".format(expectedMessage, result["error_message"])
   assert result["status"] == 404, \
      "Expected status 404, received {}".format(result["status"])
   expectedPath = "/api/blockchains/{}/concord/contracts/{}/versions/{}". \
                  format(fx.blockchainId, nonexistantContractId, contractVersion)
   assert result["path"] == expectedPath, \
      "Expected path {}, received {}".format(expectedPath, result["path"])


@pytest.mark.smoke
@pytest.mark.skip(reason="Count cannot exceed ten")
def test_transactionList_noNextField_allTransactions(fx):
   '''
   Cause there to be no "next" field by requesting all transactions.
   '''
   # txResponses = addBlocks(fx.request, fx.rpc, fx.blockchainId, 1)
   # txList = fx.request.getTransactionList(fx.blockchainId, count=12)
   pass

@pytest.mark.smoke
def test_transactionList_genesisBlockTransactions(fx):
   '''
   Verify that the transactions from the genesis block appear in the
   transaction list.
   '''
   genesisBlock = fx.request.getBlockByNumber(fx.blockchainId, 0)
   genTxs = []

   for genTx in genesisBlock["transactions"]:
      genTxs.append(genTx["hash"])

   q = queue.Queue(len(genTxs))
   nextUrl = None
   txList = fx.request.getTransactionList(fx.blockchainId)

   while True:
      for tx in txList["transactions"]:
         if q.full():
            q.get()
         q.put(tx["hash"])

      if "next" in txList.keys():
         txList = fx.request.getNextTransactionList(txList["next"])
      else:
         break

   assert q.full, "Expected to get at least {} transactions.".format(len(genTxs))

   while not q.empty():
      tx = q.get()
      assert tx in genTxs, "Expected {} in {}.".format(tx, genTxs)


@pytest.mark.smoke
def test_transactionList_newItems_onePage(fx):
   '''
   Add some transactions, get a page of transactions, and ensure
   they are there.
   '''
   expectedTxHashes = []
   receivedTxHashes = []

   for receipt in addBlocks(fx.request, fx.rpc, fx.blockchainId, 3):
      expectedTxHashes.append(receipt["transactionHash"])

   for tx in fx.request.getTransactionList(fx.blockchainId)["transactions"]:
      receivedTxHashes.append(tx["hash"])

   for expectedTxHash in expectedTxHashes:
      assert expectedTxHash in receivedTxHashes, \
         "Expected {} in {}".format(expectedTxHash, receivedTxHashes)


@pytest.mark.smoke
def test_transactionList_spanPages(fx):
   '''
   Add transactions and get them back via checking small pages,
   using the "next" field.
   '''
   trCount = 10
   expectedTxHashes = []

   for receipt in addBlocks(fx.request, fx.rpc, fx.blockchainId, trCount):
      expectedTxHashes.append(receipt["transactionHash"])

   expectedTxHashes.reverse()

   receivedTrList1 = fx.request.getTransactionList(fx.blockchainId, count=int((trCount / 2)))
   nextUrl = receivedTrList1['next']
   receivedTrList1 = list(map(lambda x : x['hash'], receivedTrList1['transactions']))

   assert (expectedTxHashes[:5] == receivedTrList1), \
      "Transaction list query did not return correct transactions"

   receivedTrList2 = fx.request.getNextTransactionList(nextUrl)
   receivedTrList2 = list(map(lambda x : x['hash'], receivedTrList2['transactions']))

   assert expectedTxHashes[5:] == receivedTrList2[:5], \
      "Transaction list query did not return correct transactions"


def _test_transactionList_pageSize_invalid(fx, testCount):
   '''
   Request <count> transactions.  We get the default (10) when invalid.
   Used to test invalid testCount values.
   '''
   expectedTxHashes = []
   receivedTxHashes = []

   addBlocks(fx.request, fx.rpc, fx.blockchainId, defaultTxInAPage-3)

   for receipt in addBlocks(fx.request, fx.rpc, fx.blockchainId, 3):
      expectedTxHashes.append(receipt["transactionHash"])

   for tx in fx.request.getTransactionList(fx.blockchainId, count=testCount)["transactions"]:
      receivedTxHashes.append(tx["hash"])

   assert len(receivedTxHashes) == defaultTxInAPage, \
      "Expected {} transactions returned".format(defaultTxInAPage)

   for expectedTx in expectedTxHashes:
      assert expectedTx in receivedTxHashes, \
         "Expected {} in {}".format(expectedTx, receivedTxHashes)


@pytest.mark.smoke
def test_transactionList_count(fx):
   txReceipt = addBlocks(fx.request, fx.rpc, fx.blockchainId, 1)[0]
   txList = fx.request.getTransactionList(fx.blockchainId, count=1)
   txList = txList['transactions']

   assert (len(txList) == 1 and txList[0]['hash'] == txReceipt['transactionHash']), \
       "Trasaction list response did not follow count parameter."


@pytest.mark.smoke
def test_transactionList_pageSize_zero(fx):
   _test_transactionList_pageSize_invalid(fx, 0)


@pytest.mark.smoke
def test_transactionList_pageSize_negative(fx):
   _test_transactionList_pageSize_invalid(fx, -1)


@pytest.mark.smoke
@pytest.mark.skip(reason="Count does not exceed ten.")
def test_transactionList_pageSize_exceedsTxCount(fx):
   '''
   Request more transactions than exist.  We can estimate the
   number of transactions by looking at the block count because
   we only store one transaction per block.  But maybe that will
   change; multiple transactions per block was suggested in a
   conversation regarding ways to improve performance.
   '''
   pass

@pytest.mark.smoke
def test_transactionList_paging_latest_invalid(fx):
   '''
   Pass in an invalid hash for latest.
   (Valid values for latest are tested via the "next" url.)
   '''
   try:
      fx.request.getTransactionList(fx.blockchainId, latest="aa")
      assert False, "Expected to get an error fetching with latest being an invalid hash."
   except Exception as ex:
      errorMessage = "latest transaction not found"
      assert errorMessage in str(ex), \
         "Expected error message {}".format(errorMessage)


@pytest.mark.smoke
def test_transactionList_max_size(fx):
   addBlocks(fx.request, fx.rpc, fx.blockchainId, defaultTxInAPage+1)
   txList = fx.request.getTransactionList(fx.blockchainId, count=1000)
   txList = txList['transactions']
   assert len(txList) == defaultTxInAPage, \
      "Expected maximum page size to be {}".format(defaultTxInAPage)


@pytest.mark.smoke
def test_transactionList_fields(fx):
   txReceipt = addBlocks(fx.request, fx.rpc, fx.blockchainId, 1)[0]
   txList = fx.request.getTransactionList(fx.blockchainId)
   found = False

   for tx in txList["transactions"]:
      if txReceipt["transactionHash"] == tx["hash"]:
         found = True
         assert util.numbers_strings.trimHexIndicator(helloFunction) in tx["input"], \
            "Expected the hello function '{}' to be in the input '{}'.".format(helloFunction,tx["input"])
         assert tx["block_hash"] == txReceipt["blockHash"], "Block hash did not match."
         assert int(tx["block_number"]) == int(txReceipt["blockNumber"], 16), "Block number did not match."
         assert tx["from"] == txReceipt["from"], "From user did not match."
         assert tx["contract_address"] == txReceipt["contractAddress"], "Contract address did not match."
         assert tx["value"] == "0x0", "Value was not correct."
         assert tx["nonce"], "Nonce was not set."
         assert tx["url"] == "/api/concord/transactions/{}".format(txReceipt["transactionHash"]), "Url was not correct."

   assert found, "Transaction not found."
