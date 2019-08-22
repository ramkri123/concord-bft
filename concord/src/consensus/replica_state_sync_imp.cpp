// Concord
//
// Copyright (c) 2019 VMware, Inc. All Rights Reserved.
//
// This product is licensed to you under the Apache 2.0 license (the
// "License").  You may not use this product except in compliance with the
// Apache 2.0 License.
//
// This product may include a number of subcomponents with separate copyright
// notices and license terms. Your use of these subcomponents is subject to the
// terms and conditions of the subcomponent's license, as noted in the LICENSE
// file.
//

#include "replica_state_sync_imp.hpp"

#include "storage/concord_metadata_storage.h"

using concord::storage::BlockchainDBAdapter;
using concord::storage::BlockId;
using concord::storage::ConcordMetadataStorage;
using concord::storage::ILocalKeyValueStorageReadOnly;
using concord::storage::Key;
using concord::storage::KeyManipulator;

namespace concord {
namespace consensus {

uint64_t ReplicaStateSyncImp::execute(log4cplus::Logger &logger,
                                      BlockchainDBAdapter &bcDBAdapter,
                                      ILocalKeyValueStorageReadOnly &kvs,
                                      BlockId lastReachableBlockId,
                                      uint64_t lastExecutedSeqNum) {
  ConcordMetadataStorage roKvs(kvs);
  BlockId blockId = lastReachableBlockId;
  uint64_t blockSeqNum = 0;
  uint64_t removedBlocksNum = 0;
  Key key = roKvs.BlockMetadataKey();
  do {
    blockSeqNum = roKvs.GetBlockMetadata(key);
    LOG4CPLUS_DEBUG(
        logger, "Block Metadata key = " << key << ", blockId = " << blockId
                                        << ", blockSeqNum = " << blockSeqNum);
    if (blockSeqNum <= lastExecutedSeqNum) {
      LOG4CPLUS_INFO(logger, "Replica state is in sync; removedBlocksNum is "
                                 << removedBlocksNum);
      return removedBlocksNum;
    }
    // SBFT State Metadata is not in sync with SBFT State.
    // Remove blocks which sequence number is greater than lastExecutedSeqNum.
    bcDBAdapter.deleteBlockAndItsKeys(blockId--);
    ++removedBlocksNum;
  } while (blockId);
  return removedBlocksNum;
}

}  // namespace consensus
}  // namespace concord
