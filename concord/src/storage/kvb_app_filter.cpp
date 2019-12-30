// Copyright 2019 VMware, all rights reserved
//
// Filtered access to the KV Blockchain.

#include "kvb_app_filter.h"

#include <log4cplus/logger.h>
#include <log4cplus/loggingmacros.h>
#include <boost/lockfree/spsc_queue.hpp>
#include <cassert>
#include <chrono>
#include <sstream>

#include "kv_types.hpp"
#include "kvb_key_types.h"
// TODO: SetOfKeyValuePairs needs a hash definition provided in hash_defs.h
// It should be included in kv_types.hpp
#include "hash_defs.h"

using namespace std::chrono_literals;

using boost::lockfree::spsc_queue;

using concordUtils::BlockId;
using concordUtils::Key;
using concordUtils::KeyValuePair;
using concordUtils::SetOfKeyValuePairs;
using concordUtils::Status;

namespace concord {
namespace storage {

SetOfKeyValuePairs KvbAppFilter::FilterKeyValuePairs(
    const SetOfKeyValuePairs &kvs, const std::string &key_prefix) {
  assert(type_ == KvbAppFilter::kDaml);
  char kvb_key_id = concord::storage::kKvbKeyDaml;

  SetOfKeyValuePairs filtered_kvs;

  for (const auto &[key, value] : kvs) {
    // Filter by appliction type
    if (key[0] != kvb_key_id) {
      continue;
    }

    // Filter by key prefix
    if (key.toString().compare(1, key_prefix.size(), key_prefix) != 0) {
      continue;
    }

    // Strip KVB key type
    Key new_key =
        key.subsliver(sizeof kvb_key_id, key.length() - (sizeof kvb_key_id));

    filtered_kvs.insert({new_key, value});
  }

  return filtered_kvs;
}

KvbUpdate KvbAppFilter::FilterUpdate(const KvbUpdate &update,
                                     const std::string &key_prefix) {
  return KvbUpdate{update.first,
                   FilterKeyValuePairs(update.second, key_prefix)};
}

size_t KvbAppFilter::HashUpdate(const KvbUpdate update) {
  size_t hash = std::hash<string>{}(std::to_string(update.first));
  for (const auto &[key, value] : update.second) {
    // (key1 XOR value1) XOR (key2 XOR value2) ...
    auto key_hash = std::hash<string>{}(string{key.data(), key.length()});
    key_hash ^= std::hash<string>{}(string{value.data(), value.length()});
    hash ^= key_hash;
  }
  return hash;
}

void KvbAppFilter::ReadBlockRange(BlockId block_id_start, BlockId block_id_end,
                                  const std::string &key_prefix,
                                  spsc_queue<KvbUpdate> &queue_out,
                                  const std::atomic_bool &stop_execution) {
  assert(block_id_start <= block_id_end);
  BlockId block_id(block_id_start);

  SetOfKeyValuePairs kvb_kvs;

  LOG4CPLUS_INFO(logger_,
                 "ReadBlockRange block " << block_id << " to " << block_id_end);

  for (; block_id <= block_id_end; ++block_id) {
    Status status = rostorage_->getBlockData(block_id, kvb_kvs);
    if (!status.isOK()) {
      std::stringstream msg;
      msg << "Couldn't retrieve block data for block id " << block_id;
      throw KvbReadError(msg.str());
    }

    KvbUpdate update{block_id, FilterKeyValuePairs(kvb_kvs, key_prefix)};
    while (!stop_execution) {
      if (queue_out.push(update)) {
        break;
      }
      std::this_thread::sleep_for(10ms);
    }

    if (stop_execution) {
      LOG4CPLUS_WARN(logger_, "ReadBlockRange was stopped");
      break;
    }
  }
}

KvbStateHash KvbAppFilter::ReadBlockHash(BlockId block_id,
                                         const std::string &key_prefix) {
  return ReadBlockRangeHash(block_id, block_id, key_prefix);
}

KvbStateHash KvbAppFilter::ReadBlockRangeHash(BlockId block_id_start,
                                              BlockId block_id_end,
                                              const std::string &key_prefix) {
  assert(block_id_start <= block_id_end);
  BlockId block_id(block_id_start);

  SetOfKeyValuePairs kvb_kvs;

  LOG4CPLUS_INFO(logger_, "ReadBlockRangeHash block " << block_id << " to "
                                                      << block_id_end);

  size_t hash_out = 0;
  for (; block_id <= block_id_end; ++block_id) {
    Status status = rostorage_->getBlockData(block_id, kvb_kvs);
    if (!status.isOK()) {
      std::stringstream msg;
      msg << "Couldn't retrieve block data for block id " << block_id;
      throw KvbReadError(msg.str());
    }

    KvbUpdate filtered_update{block_id,
                              FilterKeyValuePairs(kvb_kvs, key_prefix)};
    hash_out ^= HashUpdate(filtered_update);
  }
  return hash_out;
}

}  // namespace storage
}  // namespace concord
