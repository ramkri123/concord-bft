// Copyright 2018 VMware, all rights reserved
//
// KV Blockchain replica implementation.

#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifndef _WIN32
#include <sys/param.h>
#include <unistd.h>
#endif

#include <inttypes.h>
#include <log4cplus/loggingmacros.h>
#include <CommDefs.hpp>
#include <cassert>
#include <chrono>
#include <cstdlib>
#include "Comparators.h"
#include "HexTools.h"
#include "ReplicaImp.h"
#include "Threading.h"
#include "sliver.hpp"

using log4cplus::Logger;
using namespace bftEngine;
using namespace Blockchain;

struct blockEntry {
  uint32_t keyOffset;
  uint32_t keySize;
  uint32_t valOffset;
  uint32_t valSize;
};
struct blockHeader {
  uint32_t numberOfElements;
  uint32_t parentDigestLength;
  int8_t parentDigest[SimpleBlockchainStateTransfer::BLOCK_DIGEST_SIZE];
  blockEntry entries[1];  // n>0 entries
};

/**
 * Opens the database and creates the replica thread. Replica state moves to
 * Starting.
 */
Status ReplicaImp::start() {
  if (m_currentRepStatus != RepStatus::Idle) {
    return Status::IllegalOperation("todo");
  }

  m_currentRepStatus = RepStatus::Starting;

  m_replicaPtr = bftEngine::Replica::createNewReplica(
      &m_replicaConfig, m_cmdHandler, m_stateTransfer, m_ptrComm, nullptr);

  m_replicaPtr->start();
  m_currentRepStatus = RepStatus::Running;

  /// TODO(IG, GG)
  /// add return value to start/stop

  return Status::OK();
}

/**
 * Closes the database. Call `wait()` after this to wait for thread to stop.
 */
Status ReplicaImp::stop() {
  m_currentRepStatus = RepStatus::Stopping;
  m_bcDbAdapter->getDb()->close();
  m_replicaPtr->stop();
  m_currentRepStatus = RepStatus::Idle;
  return Status::OK();
}

/**
 * Wait for replica thread to stop.
 */
Status ReplicaImp::wait() {
  if (m_currentRepStatus != RepStatus::Starting &&
      m_currentRepStatus != RepStatus::Running) {
    return Status::IllegalOperation("todo");
  }

  threadJoin(m_thread);

  return Status::OK();
}

ReplicaImp::RepStatus ReplicaImp::getReplicaStatus() const {
  return m_currentRepStatus;
}

const ILocalKeyValueStorageReadOnly &ReplicaImp::getReadOnlyStorage() {
  return m_InternalStorageWrapperForIdleMode;
}

Status ReplicaImp::addBlockToIdleReplica(const SetOfKeyValuePairs &updates) {
  if (getReplicaStatus() != IReplica::RepStatus::Idle) {
    return Status::IllegalOperation("");
  }

  BlockId d;
  return addBlockInternal(updates, d);
}

Status ReplicaImp::get(Sliver key, Sliver &outValue) const {
  // TODO(GG): check legality of operation (the method should be invocked from
  // the replica's internal thread)

  BlockId dummy;
  return getInternal(lastBlock, key, outValue, dummy);
}

Status ReplicaImp::get(BlockId readVersion, Sliver key, Sliver &outValue,
                       BlockId &outBlock) const {
  // TODO(GG): check legality of operation (the method should be invocked from
  // the replica's internal thread)

  return getInternal(readVersion, key, outValue, outBlock);
}

BlockId ReplicaImp::getLastBlock() const { return lastBlock; }

Status ReplicaImp::getBlockData(BlockId blockId,
                                SetOfKeyValuePairs &outBlockData) const {
  // TODO(GG): check legality of operation (the method should be invocked from
  // the replica's internal thread)

  Sliver block = getBlockInternal(blockId);

  if (block.length() == 0) {
    return Status::NotFound("todo");
  }

  outBlockData = fetchBlockData(block);

  return Status::OK();
}

Status ReplicaImp::mayHaveConflictBetween(Sliver key, BlockId fromBlock,
                                          BlockId toBlock, bool &outRes) const {
  // TODO(GG): add assert or print warning if fromBlock==0 (all keys have a
  // conflict in block 0)

  // we conservatively assume that we have a conflict
  outRes = true;

  Sliver dummy;
  BlockId block = 0;
  Status s = getInternal(toBlock, key, dummy, block);
  if (s.isOK() && block < fromBlock) {
    outRes = false;
  }

  return s;
}

ILocalKeyValueStorageReadOnlyIterator *ReplicaImp::getSnapIterator() const {
  return m_InternalStorageWrapperForIdleMode.getSnapIterator();
}

Status ReplicaImp::freeSnapIterator(
    ILocalKeyValueStorageReadOnlyIterator *iter) const {
  return m_InternalStorageWrapperForIdleMode.freeSnapIterator(iter);
}

void ReplicaImp::monitor() const {
  m_InternalStorageWrapperForIdleMode.monitor();
}

Status ReplicaImp::addBlock(const SetOfKeyValuePairs &updates,
                            BlockId &outBlockId) {
  // TODO(GG): check legality of operation (the method should be invocked from
  // the replica's internal thread)

  // TODO(GG): what do we want to do with several identical keys in the same
  // block?

  return addBlockInternal(updates, outBlockId);
}

void ReplicaImp::set_command_handler(Blockchain::ICommandsHandler *handler) {
  m_cmdHandler = handler;
}

ReplicaImp::ReplicaImp(Blockchain::CommConfig &commConfig,
                       ReplicaConsensusConfig &replicaConfig,
                       BlockchainDBAdapter *dbAdapter)
    : logger(log4cplus::Logger::getInstance("com.vmware.concord.kvb")),
      m_currentRepStatus(RepStatus::Idle),
      m_InternalStorageWrapperForIdleMode(this),
      m_bcDbAdapter(dbAdapter),
      lastBlock(0) {
  m_replicaConfig.cVal = replicaConfig.cVal;
  m_replicaConfig.fVal = replicaConfig.fVal;
  m_replicaConfig.replicaId = replicaConfig.replicaId;
  m_replicaConfig.autoViewChangeEnabled = replicaConfig.autoViewChangeEnabled;
  m_replicaConfig.concurrencyLevel = replicaConfig.concurrencyLevel;
  m_replicaConfig.numOfClientProxies = replicaConfig.numOfClientProxies;
  m_replicaConfig.publicKeysOfReplicas = replicaConfig.publicKeysOfReplicas;
  m_replicaConfig.replicaPrivateKey = replicaConfig.replicaPrivateKey;
  m_replicaConfig.statusReportTimerMillisec =
      replicaConfig.statusReportTimerMillisec;
  m_replicaConfig.viewChangeTimerMillisec =
      replicaConfig.viewChangeTimerMillisec;
  /// TODO(IG): the below part of the config should be initialized within the
  /// BFT engine
  m_replicaConfig.thresholdSignerForCommit =
      replicaConfig.thresholdSignerForCommit;
  m_replicaConfig.thresholdSignerForExecution =
      replicaConfig.thresholdSignerForExecution;
  m_replicaConfig.thresholdSignerForOptimisticCommit =
      replicaConfig.thresholdSignerForOptimisticCommit;
  m_replicaConfig.thresholdSignerForSlowPathCommit =
      replicaConfig.thresholdSignerForSlowPathCommit;
  m_replicaConfig.thresholdVerifierForCommit =
      replicaConfig.thresholdVerifierForCommit;
  m_replicaConfig.thresholdVerifierForExecution =
      replicaConfig.thresholdVerifierForExecution;
  m_replicaConfig.thresholdVerifierForOptimisticCommit =
      replicaConfig.thresholdVerifierForOptimisticCommit;
  m_replicaConfig.thresholdVerifierForSlowPathCommit =
      replicaConfig.thresholdVerifierForSlowPathCommit;

  /// TODO(IG): since we want to decouple concord and bft by KVB layer,
  /// concord should not know about inner bft comm types. Instead, it should
  // have its own setting which comm to use. Currently using UDP hardcoded
  bftEngine::PlainUdpConfig config(
      commConfig.listenIp, commConfig.listenPort, commConfig.bufferLength,
      commConfig.nodes, commConfig.selfId, commConfig.statusCallback);

  m_ptrComm = bftEngine::CommFactory::create(config);

  bftEngine::SimpleBlockchainStateTransfer::Config c;
  c.myReplicaId = m_replicaConfig.replicaId, c.cVal = m_replicaConfig.cVal,
  c.fVal = m_replicaConfig.fVal;

  m_appState = new BlockchainAppState(this);
  m_stateTransfer = SimpleBlockchainStateTransfer::create(c, m_appState, false);
}

ReplicaImp::~ReplicaImp() {
  if (m_stateTransfer) {
    if (m_stateTransfer->isRunning()) {
      m_stateTransfer->stopRunning();
    }
    delete m_stateTransfer;
  }

  if (m_replicaPtr) {
    if (m_replicaPtr->isRunning()) {
      m_replicaPtr->stop();
    }
    delete m_replicaPtr;
  }
}

Status ReplicaImp::addBlockInternal(const SetOfKeyValuePairs &updates,
                                    BlockId &outBlockId) {
  lastBlock++;
  m_appState->m_lastReachableBlock++;

  BlockId block = lastBlock;
  SetOfKeyValuePairs updatesInNewBlock;

  LOG4CPLUS_DEBUG(logger,
                  "addBlockInternal: Got " << updates.size() << " updates");

  SimpleBlockchainStateTransfer::StateTransferDigest stDigest;
  if (block > 1) {
    Sliver parentBlockData;
    bool found;
    m_bcDbAdapter->getBlockById(block - 1, parentBlockData, found);
    if (!found || parentBlockData.length() == 0) {
      //(IG): panic, data corrupted
      LOG4CPLUS_FATAL(logger, "addBlockInternal: no block or block data for id "
                                  << block - 1);
      exit(1);
    }

    SimpleBlockchainStateTransfer::computeBlockDigest(
        block - 1, reinterpret_cast<const char *>(parentBlockData.data()),
        parentBlockData.length(), &stDigest);
  } else {
    memset(stDigest.content, 0,
           SimpleBlockchainStateTransfer::BLOCK_DIGEST_SIZE);
  }

  Sliver blockRaw =
      createBlockFromUpdates(updates, updatesInNewBlock, stDigest);
  Status s = m_bcDbAdapter->addBlock(block, blockRaw);
  if (!s.isOK()) {
    LOG4CPLUS_ERROR(logger, "Failed to add block");
    return s;
  }

  for (SetOfKeyValuePairs::iterator it = updatesInNewBlock.begin();
       it != updatesInNewBlock.end(); ++it) {
    const KeyValuePair &kvPair = *it;

    LOG4CPLUS_DEBUG(logger, "Adding for " << kvPair.first << " the value "
                                          << kvPair.second);

    Status s = m_bcDbAdapter->updateKey(kvPair.first, block, kvPair.second);
    if (!s.isOK()) {
      LOG4CPLUS_ERROR(logger, "Failed to update key " << kvPair.first
                                                      << ", block " << block);
      return s;
    }
  }

  outBlockId = block;

  return Status::OK();
}

Status ReplicaImp::getInternal(BlockId readVersion, Sliver key,
                               Sliver &outValue, BlockId &outBlock) const {
  Status s =
      m_bcDbAdapter->getKeyByReadVersion(readVersion, key, outValue, outBlock);
  if (!s.isOK()) {
    LOG4CPLUS_ERROR(logger, "Failed to get key " << key << " by read version "
                                                 << readVersion);
    return s;
  }

  return Status::OK();
}

void ReplicaImp::revertBlock(BlockId blockId) {
  Sliver blockRaw;
  bool found;
  Status s = m_bcDbAdapter->getBlockById(blockId, blockRaw, found);
  if (!s.isOK()) {
    // the replica is corrupted!
    // TODO(GG): what do we want to do now?
    LOG4CPLUS_FATAL(logger, "replica may be corrupted");
    // TODO(GG): how do we want to handle this - restart replica?
    exit(1);
  }

  if (found && blockRaw.length() > 0) {
    blockHeader *header = (blockHeader *)blockRaw.data();

    for (size_t i = 0; i < header->numberOfElements; i++) {
      const Sliver keySliver(blockRaw, header->entries[i].keyOffset,
                             header->entries[i].keySize);

      Status s = m_bcDbAdapter->delKey(keySliver, blockId);
      if (!s.isOK()) {
        // TODO(SG): What to do?
        LOG4CPLUS_FATAL(logger, "Failed to delete key");
        exit(1);
      }
    }
  }

  if (found) {
    m_bcDbAdapter->delBlock(blockId);
  }
}

void ReplicaImp::insertBlockInternal(BlockId blockId, Sliver block) {
  if (blockId > lastBlock) {
    lastBlock = blockId;
  }
  // when ST runs, blocks arrive in batches in reverse order. we need to keep
  // track on the "Gap" and to close it. Only when it is closed, the last
  // reachable block becomes the same as the last block
  if (blockId == m_appState->m_lastReachableBlock + 1) {
    m_appState->m_lastReachableBlock = lastBlock;
  }

  bool found = false;
  Sliver blockRaw;
  Status s = m_bcDbAdapter->getBlockById(blockId, blockRaw, found);
  if (!s.isOK()) {
    // the replica is corrupted!
    // TODO(GG): what do we want to do now?
    LOG4CPLUS_FATAL(logger, "replica may be corrupted\n\n");
    // TODO(GG): how do we want to handle this - restart replica?
    exit(1);
  }

  // if we already have a block with the same ID
  if (found && blockRaw.length() > 0) {
    if (blockRaw.length() != block.length() ||
        memcmp(blockRaw.data(), block.data(), block.length())) {
      // the replica is corrupted !
      // TODO(GG): what do we want to do now ?
      LOG4CPLUS_ERROR(logger, "found block "
                                  << blockId << ", size in db is "
                                  << blockRaw.length() << ", inserted is "
                                  << block.length() << ", data in db "
                                  << blockRaw << ", data inserted " << block);
      LOG4CPLUS_ERROR(
          logger,
          "Block size test "
              << (blockRaw.length() != block.length()) << ", block data test "
              << (memcmp(blockRaw.data(), block.data(), block.length())));

      // TODO(GG): If new block is empty, just revert block
      revertBlock(blockId);

      // TODO(GG): how do we want to handle this - restart replica?
      // exit(1);
      return;
    }
  } else {
    if (block.length() > 0) {
      blockHeader *header = (blockHeader *)block.data();

      for (size_t i = 0; i < header->numberOfElements; i++) {
        const Sliver keySliver(block, header->entries[i].keyOffset,
                               header->entries[i].keySize);
        const Sliver valSliver(block, header->entries[i].valOffset,
                               header->entries[i].valSize);

        const KeyIDPair pk(keySliver, blockId);

        Status s = m_bcDbAdapter->updateKey(pk.key, pk.blockId, valSliver);
        if (!s.isOK()) {
          // TODO(SG): What to do?
          LOG4CPLUS_FATAL(logger, "Failed to update key");
          exit(1);
        }
      }

      Status s = m_bcDbAdapter->addBlock(blockId, block);
      if (!s.isOK()) {
        // TODO(SG): What to do?
        printf("Failed to add block");
        exit(1);
      }
    } else {
      Status s = m_bcDbAdapter->addBlock(blockId, block);
      if (!s.isOK()) {
        // TODO(SG): What to do?
        printf("Failed to add block");
        exit(1);
      }
    }
  }
}

Sliver ReplicaImp::getBlockInternal(BlockId blockId) const {
  assert(blockId <= lastBlock);
  Sliver retVal;

  bool found;
  Status s = m_bcDbAdapter->getBlockById(blockId, retVal, found);
  if (!s.isOK()) {
    // TODO(SG): To do something smarter
    LOG4CPLUS_ERROR(logger, "An error occurred in get block");
    return Sliver();
  }

  if (!found) {
    return Sliver();
  } else {
    return retVal;
  }
}

ReplicaImp::StorageWrapperForIdleMode::StorageWrapperForIdleMode(
    const ReplicaImp *r)
    : rep(r) {}

Status ReplicaImp::StorageWrapperForIdleMode::get(Sliver key,
                                                  Sliver &outValue) const {
  if (rep->getReplicaStatus() != IReplica::RepStatus::Idle) {
    return Status::IllegalOperation("");
  }

  return rep->get(key, outValue);
}

Status ReplicaImp::StorageWrapperForIdleMode::get(BlockId readVersion,
                                                  Sliver key, Sliver &outValue,
                                                  BlockId &outBlock) const {
  if (rep->getReplicaStatus() != IReplica::RepStatus::Idle) {
    return Status::IllegalOperation("");
  }

  return rep->get(readVersion, key, outValue, outBlock);
}

BlockId ReplicaImp::StorageWrapperForIdleMode::getLastBlock() const {
  return rep->getLastBlock();
}

Status ReplicaImp::StorageWrapperForIdleMode::getBlockData(
    BlockId blockId, SetOfKeyValuePairs &outBlockData) const {
  if (rep->getReplicaStatus() != IReplica::RepStatus::Idle) {
    return Status::IllegalOperation("");
  }

  Sliver block = rep->getBlockInternal(blockId);

  if (block.length() == 0) {
    return Status::NotFound("todo");
  }

  outBlockData = ReplicaImp::fetchBlockData(block);

  return Status::OK();
}

Status ReplicaImp::StorageWrapperForIdleMode::mayHaveConflictBetween(
    Sliver key, BlockId fromBlock, BlockId toBlock, bool &outRes) const {
  outRes = true;

  Sliver dummy;
  BlockId block = 0;
  Status s = rep->getInternal(toBlock, key, dummy, block);

  if (s.isOK() && block < fromBlock) {
    outRes = false;
  }

  return s;
}

ILocalKeyValueStorageReadOnlyIterator *
ReplicaImp::StorageWrapperForIdleMode::getSnapIterator() const {
  return new StorageIterator(this->rep);
}

Status ReplicaImp::StorageWrapperForIdleMode::freeSnapIterator(
    ILocalKeyValueStorageReadOnlyIterator *iter) const {
  if (iter == NULL) {
    return Status::InvalidArgument("Invalid iterator");
  }

  StorageIterator *storageIter = (StorageIterator *)iter;
  Status s = storageIter->freeInternalIterator();
  delete storageIter;
  return s;
}

void ReplicaImp::StorageWrapperForIdleMode::monitor() const {
  this->rep->m_bcDbAdapter->monitor();
}

Sliver ReplicaImp::createBlockFromUpdates(
    const SetOfKeyValuePairs &updates, SetOfKeyValuePairs &outUpdatesInNewBlock,
    SimpleBlockchainStateTransfer::StateTransferDigest &parentDigest) {
  // TODO(GG): overflow handling ....
  // TODO(SG): How? Right now - will put empty block instead

  assert(outUpdatesInNewBlock.size() == 0);

  uint32_t blockBodySize = 0;
  uint16_t numOfElemens = 0;
  for (auto it = updates.begin(); it != updates.end(); ++it) {
    numOfElemens++;
    // body is all of the keys and values strung together
    blockBodySize += (it->first.length() + it->second.length());
  }

  const uint32_t headerSize = sizeof(blockHeader::numberOfElements) +
                              sizeof(blockHeader::parentDigestLength) +
                              SimpleBlockchainStateTransfer::BLOCK_DIGEST_SIZE +
                              sizeof(blockEntry) * (numOfElemens);

  const uint32_t blockSize = headerSize + blockBodySize;

  try {
    uint8_t *blockBuffer = new uint8_t[blockSize];
    memset(blockBuffer, 0, blockSize);
    Sliver blockSliver(blockBuffer, blockSize);

    blockHeader *header = (blockHeader *)blockBuffer;
    memcpy(header->parentDigest, parentDigest.content,
           SimpleBlockchainStateTransfer::BLOCK_DIGEST_SIZE);
    header->parentDigestLength =
        SimpleBlockchainStateTransfer::BLOCK_DIGEST_SIZE;

    int16_t idx = 0;
    header->numberOfElements = numOfElemens;
    int32_t currentOffset = headerSize;
    for (auto it = updates.begin(); it != updates.end(); ++it) {
      const KeyValuePair &kvPair = *it;

      // key
      header->entries[idx].keyOffset = currentOffset;
      header->entries[idx].keySize = kvPair.first.length();
      memcpy(blockBuffer + currentOffset, kvPair.first.data(),
             kvPair.first.length());
      Sliver newKey(blockSliver, currentOffset, kvPair.first.length());

      currentOffset += kvPair.first.length();

      // value
      header->entries[idx].valOffset = currentOffset;
      header->entries[idx].valSize = kvPair.second.length();
      memcpy(blockBuffer + currentOffset, kvPair.second.data(),
             kvPair.second.length());
      Sliver newVal(blockSliver, currentOffset, kvPair.second.length());

      currentOffset += kvPair.second.length();

      // add to outUpdatesInNewBlock
      KeyValuePair newKVPair(newKey, newVal);
      outUpdatesInNewBlock.insert(newKVPair);

      idx++;
    }
    assert(idx == numOfElemens);
    assert((uint32_t)currentOffset == blockSize);

    return blockSliver;
  } catch (std::bad_alloc &ba) {
    LOG4CPLUS_ERROR(
        Logger::getInstance("com.vmware.concord.kvb"),
        "Failed to alloc size " << blockSize << ", error: " << ba.what());
    uint8_t *emptyBlockBuffer = new uint8_t[1];
    memset(emptyBlockBuffer, 0, 1);
    return Sliver(emptyBlockBuffer, 1);
  }
}

SetOfKeyValuePairs ReplicaImp::fetchBlockData(Sliver block) {
  SetOfKeyValuePairs retVal;

  if (block.length() > 0) {
    blockHeader *header = (blockHeader *)block.data();

    for (size_t i = 0; i < header->numberOfElements; i++) {
      Sliver keySliver(block, header->entries[i].keyOffset,
                       header->entries[i].keySize);
      Sliver valSliver(block, header->entries[i].valOffset,
                       header->entries[i].valSize);

      KeyValuePair kv(keySliver, valSliver);

      retVal.insert(kv);
    }
  }

  return retVal;
}

IReplica *Blockchain::createReplica(Blockchain::CommConfig &commConfig,
                                    ReplicaConsensusConfig &config,
                                    IDBClient *db) {
  LOG4CPLUS_DEBUG(Logger::getInstance("com.vmware.concord.kvb"),
                  "Creating replica");
  BlockchainDBAdapter *dbAdapter = new BlockchainDBAdapter(db);

  auto r = new Blockchain::ReplicaImp(commConfig, config, dbAdapter);

  // Initialization of the database object is done here so that we can
  // read the latest block number and take a decision regarding
  // genesis block creation.
  Status s = db->init();

  if (!s.isOK()) {
    LOG4CPLUS_FATAL(Logger::getInstance("com.vmware.concord.kvb"),
                    "Failure in Database Initialization, status: " << s);
    throw ReplicaInitException("Failure in Database Initialization");
  }

  // Get the latest block count from persistence.
  // Will always be 0 for either InMemory mode or for persistence mode
  // when no database files exist.
  r->lastBlock = dbAdapter->getLatestBlock();
  r->m_appState->m_lastReachableBlock = dbAdapter->getLastReachableBlock();

  return r;
}

void Blockchain::release(IReplica *r) { delete r; }

ReplicaImp::StorageIterator::StorageIterator(const ReplicaImp *r)
    : logger(log4cplus::Logger::getInstance("com.vmware.concord.kvb")), rep(r) {
  m_iter = r->getBcDbAdapter()->getIterator();
  m_currentBlock = r->getLastBlock();
}

KeyValuePair ReplicaImp::StorageIterator::first(BlockId readVersion,
                                                BlockId &actualVersion,
                                                bool &isEnd) {
  Key key;
  Value value;
  Status s = rep->getBcDbAdapter()->first(m_iter, readVersion, actualVersion,
                                          isEnd, key, value);

  if (s.isNotFound()) {
    isEnd = true;
    m_current = KeyValuePair();
    return m_current;
  }

  if (!s.isOK()) {
    LOG4CPLUS_ERROR(logger, "Failed to get first");
    exit(1);
  }

  m_isEnd = isEnd;
  m_current = KeyValuePair(key, value);

  return m_current;
}

KeyValuePair ReplicaImp::StorageIterator::seekAtLeast(BlockId readVersion,
                                                      Key key,
                                                      BlockId &actualVersion,
                                                      bool &isEnd) {
  Key actualKey;
  Value value;
  Status s = rep->getBcDbAdapter()->seekAtLeast(
      m_iter, key, readVersion, actualVersion, actualKey, value, isEnd);

  if (s.isNotFound()) {
    isEnd = true;
    m_current = KeyValuePair();
    return m_current;
  }

  if (!s.isOK()) {
    LOG4CPLUS_FATAL(logger, "Failed to seek at least");
    exit(1);
  }

  m_isEnd = isEnd;
  m_current = KeyValuePair(actualKey, value);
  return m_current;
}

/**
 * TODO(SG): There is a question mark regarding on these APIs. Suppose I have
 * (k0,2), (k1,7), (k2,4) and I request next(k0,5). Do we return end() (because
 * k1 cannot be returned), or do we return k2?  I implemented the second choice,
 * as it makes better sense. The world in Block 5 did not include k1, that's
 * perfectly OK.
 */
// Note: key,readVersion must exist in map already
KeyValuePair ReplicaImp::StorageIterator::next(BlockId readVersion, Key key,
                                               BlockId &actualVersion,
                                               bool &isEnd) {
  Key nextKey;
  Value nextValue;
  Status s = rep->getBcDbAdapter()->next(m_iter, readVersion, nextKey,
                                         nextValue, actualVersion, isEnd);

  if (s.isNotFound()) {
    isEnd = true;
    m_current = KeyValuePair();
    return m_current;
  }

  if (!s.isOK()) {
    LOG4CPLUS_FATAL(logger, "Failed to get next");
    exit(1);
  }

  m_isEnd = isEnd;
  m_current = KeyValuePair(nextKey, nextValue);
  return m_current;
}

KeyValuePair ReplicaImp::StorageIterator::getCurrent() {
  Key key;
  Value value;
  Status s = rep->getBcDbAdapter()->getCurrent(m_iter, key, value);

  if (!s.isOK()) {
    LOG4CPLUS_FATAL(logger, "Failed to get current");
    exit(1);
  }

  m_current = KeyValuePair(key, value);
  return m_current;
}

bool ReplicaImp::StorageIterator::isEnd() {
  bool isEnd;
  Status s = rep->getBcDbAdapter()->isEnd(m_iter, isEnd);

  if (!s.isOK()) {
    LOG4CPLUS_FATAL(logger, "Failed to get current");
    exit(1);
  }

  return isEnd;
}

Status ReplicaImp::StorageIterator::freeInternalIterator() {
  return rep->getBcDbAdapter()->freeIterator(m_iter);
}

/*
 * These functions are used by the ST module to interact with the KVB
 */
ReplicaImp::BlockchainAppState::BlockchainAppState(
    Blockchain::ReplicaImp *const parent)
    : m_ptrReplicaImpl{parent},
      m_logger{log4cplus::Logger::getInstance("blockchainappstate")} {}

/*
 * This method assumes that *outBlock is big enough to hold block content
 * The caller is the owner of the memory
 */
bool ReplicaImp::BlockchainAppState::getBlock(uint64_t blockId, char *outBlock,
                                              uint32_t *outBlockSize) {
  Sliver res = m_ptrReplicaImpl->getBlockInternal(blockId);
  if (0 == res.length()) {
    // in normal state it should not happen. If it happened - the data is
    // corrupted
    LOG4CPLUS_FATAL(m_logger, "Block not found, ID: " << blockId);
    exit(1);
  }

  *outBlockSize = res.length();
  memcpy(outBlock, res.data(), res.length());
  return true;
}

bool ReplicaImp::BlockchainAppState::hasBlock(uint64_t blockId) {
  Sliver res = m_ptrReplicaImpl->getBlockInternal(blockId);
  return res.length() > 0;
}

bool ReplicaImp::BlockchainAppState::getPrevDigestFromBlock(
    uint64_t blockId,
    SimpleBlockchainStateTransfer::StateTransferDigest *outPrevBlockDigest) {
  assert(blockId > 0);
  Sliver result;
  bool found;
  m_ptrReplicaImpl->m_bcDbAdapter->getBlockById(blockId, result, found);
  if (!found) {
    // in normal state it should not happen. If it happened - the data is
    // corrupted
    LOG4CPLUS_FATAL(m_logger,
                    "Block not found for parent digest, ID: " << blockId);
    exit(1);
  }

  blockHeader *bh = reinterpret_cast<blockHeader *>(result.data());
  assert(outPrevBlockDigest);
  memcpy(outPrevBlockDigest, bh->parentDigest, bh->parentDigestLength);
  return true;
}

/*
 * This method cant return false by current insertBlockInternal impl.
 */
bool ReplicaImp::BlockchainAppState::putBlock(uint64_t blockId, char *block,
                                              uint32_t blockSize) {
  uint8_t *tmpBlockPtr = new uint8_t[blockSize];
  memcpy(tmpBlockPtr, block, blockSize);
  Sliver s(tmpBlockPtr, blockSize);

  m_ptrReplicaImpl->insertBlockInternal(blockId, s);
  return true;
}

uint64_t ReplicaImp::BlockchainAppState::getLastReachableBlockNum() {
  return m_lastReachableBlock;
}

uint64_t ReplicaImp::BlockchainAppState::getLastBlockNum() {
  return m_ptrReplicaImpl->lastBlock;
}