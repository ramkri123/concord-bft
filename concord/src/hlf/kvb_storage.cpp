// Copyright 2018 VMware, all rights reserved

#include "hlf/kvb_storage.hpp"
#include <cstring>
#include <vector>
#include "common/concord_exception.hpp"
#include "concord_storage.pb.h"
#include "utils/concord_eth_hash.hpp"

using concord::consensus::Sliver;
using concord::consensus::Status;

using concord::common::BlockNotFoundException;
using concord::common::EVMException;
using concord::common::ReadOnlyModeException;
using concord::common::zero_hash;

using concord::storage::BlockId;
using concord::storage::IBlocksAppender;
using concord::storage::ILocalKeyValueStorageReadOnly;

namespace concord {
namespace hlf {

////////////////////////////////////////
// General

// Current storage versions.
const int64_t KHlfStateStorageVersion = 1;

// read-only mode
HlfKvbStorage::HlfKvbStorage(const ILocalKeyValueStorageReadOnly &ro_storage)
    : ro_storage_(ro_storage),
      ptr_block_appender_(nullptr),
      logger_(
          log4cplus::Logger::getInstance("com.vmware.concord.hlf.storage")) {}

// read-write mode
HlfKvbStorage::HlfKvbStorage(const ILocalKeyValueStorageReadOnly &ro_storage,
                             IBlocksAppender *ptr_block_appender,
                             uint64_t sequence_num)
    : ro_storage_(ro_storage),
      ptr_block_appender_(ptr_block_appender),
      logger_(
          log4cplus::Logger::getInstance("com.vmware.concord.hlf.storage")) {}

HlfKvbStorage::~HlfKvbStorage() {}

bool HlfKvbStorage::is_read_only() {
  // if we don't have a ptr_block_appender, we are read-only
  auto res = ptr_block_appender_ == nullptr;
  return res;
}

// Allow access to read-only storage object, to enabled downgrades to read-only
// HlfKvbStorage when convenient.
const concord::storage::ILocalKeyValueStorageReadOnly &
HlfKvbStorage::getReadOnlyStorage() {
  return ro_storage_;
}

////////////////////////////////////////
// ADDRESSING

Sliver HlfKvbStorage::KvbKey(uint8_t type, const string &key) const {
  const char *c = key.c_str();
  const uint8_t *p = reinterpret_cast<const uint8_t *>(c);
  int length = strlen(c);

  uint8_t *kvb_key = new uint8_t[1 + length];
  kvb_key[0] = type;

  if (length) {
    std::copy(p, p + length, kvb_key + 1);
  }
  return Sliver(kvb_key, length + 1);
}

Sliver HlfKvbStorage::KvbKey(uint8_t type, const uint8_t *bytes,
                             size_t length) const {
  uint8_t *key = new uint8_t[1 + length];
  key[0] = type;
  std::copy(bytes, bytes + length, key + 1);
  return Sliver(key, length + 1);
}

Sliver HlfKvbStorage::HlfStateKey(const string &key) const {
  return KvbKey(kTypeHlfState, key);
}

Sliver HlfKvbStorage::HlfTransactionKey(const evm_uint256be &hash) const {
  return KvbKey(kTypeHlfTransaction, hash.bytes, sizeof(evm_uint256be));
}

Sliver HlfKvbStorage::HlfBlockKey(const evm_uint256be &hash) const {
  return KvbKey(kTypeHlfBlock, hash.bytes, sizeof(evm_uint256be));
}

////////////////////////////////////////
// WRITING

// Add a key-value pair to be stored in the block. Throws ReadOnlyModeException
// if this object is in read-only mode.
void HlfKvbStorage::put(const Sliver &key, const Sliver &value) {
  if (!ptr_block_appender_) {
    throw ReadOnlyModeException();
  }

  updates_[key] = value;
}

// Add hlf transaction will append newly incoming
// HLF transaction to queue for later handling.
Status HlfKvbStorage::AddHlfTransaction(
    const com::vmware::concord::HlfRequest &hlfRequest) {
  com::vmware::concord::hlf::storage::HlfTransaction tx;

  tx.set_version(concord::common::tx_storage_version);
  tx.set_chain_id(hlfRequest.chain_id());
  tx.set_chaincode_id(hlfRequest.chaincode_name());
  tx.set_input(hlfRequest.input());
  if (hlfRequest.has_version()) {
    tx.set_chaincode_version(hlfRequest.version());
  } else {
    tx.set_chaincode_version("0");
  }
  pending_hlf_transactions_.push_back(tx);

  return Status::OK();
}

com::vmware::concord::hlf::storage::HlfBlock HlfKvbStorage::GetHlfBlock(
    uint64_t blockNumber) {
  concord::storage::SetOfKeyValuePairs out_blockData;

  Status status = ro_storage_.getBlockData(blockNumber, out_blockData);

  LOG4CPLUS_DEBUG(logger_, "Getting block number "
                               << blockNumber << " status: " << status
                               << " value.size: " << out_blockData.size());

  if (status.isOK()) {
    for (auto kvp : out_blockData) {
      if (kvp.first.data()[0] == kTypeHlfBlock) {
        com::vmware::concord::hlf::storage::HlfBlock hlfBlock;
        hlfBlock.ParseFromArray(kvp.second.data(), kvp.second.length());
        return hlfBlock;
      }
    }
  }
  throw BlockNotFoundException();
}

// Add block of hlf type to the database, this API should have more
// functionalities in the future.
Status HlfKvbStorage::WriteHlfBlock() {
  if (!ptr_block_appender_) {
    throw ReadOnlyModeException();
  }

  com::vmware::concord::hlf::storage::HlfBlock block;
  // hlf block number start from 1
  block.set_number(next_block_number());
  block.set_version(KHlfStateStorageVersion);

  LOG4CPLUS_INFO(logger_,
                 "Get Current Block number: " << current_block_number());
  // it means no block has been committed yet
  if (block.number() == 1) {
    block.set_parent_hash(zero_hash.bytes, sizeof(evm_uint256be));
  } else {
    com::vmware::concord::hlf::storage::HlfBlock parent =
        GetHlfBlock(current_block_number());
    block.set_parent_hash(parent.hash());
  }

  // handle transactions
  for (auto tx : pending_hlf_transactions_) {
    tx.set_block_id(block.number());
    tx.set_tx_index(0);  // hard code 0
    tx.set_status(0);    // find out how to
                         // verify the status

    // calculate hash according to proto bytes
    size_t size = tx.ByteSize();
    uint8_t *txTarget = new uint8_t[size];
    tx.SerializeToArray(txTarget, size);

    // TODO(lukec) replace the EthHash with the way that
    // HLF calculates the hash
    evm_uint256be txId = concord::utils::eth_hash::keccak_hash(txTarget, size);
    Sliver txAddr = HlfTransactionKey(txId);

    put(txAddr, Sliver(txTarget, size));

    block.add_transaction(txId.bytes, sizeof(evm_uint256be));
  }

  size_t size = block.ByteSize();
  uint8_t *blkTarget = new uint8_t[size];

  // set block hash
  block.SerializeToArray(blkTarget, size);

  // calculate hash according to proto bytes
  evm_uint256be blockId =
      concord::utils::eth_hash::keccak_hash(blkTarget, size);
  block.set_hash(blockId.bytes, sizeof(evm_uint256be));

  // key = TYPE_HLF_BLOCK + block hash
  Sliver blockAddr = HlfBlockKey(blockId);
  put(blockAddr, Sliver(blkTarget, size));

  pending_hlf_transactions_.clear();

  // Actually write the block
  BlockId out_blockId;
  Status status = ptr_block_appender_->addBlock(updates_, out_blockId);
  if (status.isOK()) {
    LOG4CPLUS_DEBUG(logger_, "Appended block number " << out_blockId);
  } else {
    LOG4CPLUS_ERROR(logger_, "Failed to append block");
  }

  // Prepare to stage another block
  LOG4CPLUS_DEBUG(logger_, "ready to reset");
  reset();
  LOG4CPLUS_DEBUG(logger_, "finished the reset");
  return status;
}

// Drop all pending updates_.
void HlfKvbStorage::reset() {
  // Slivers release their memory automatically.
  updates_.clear();
}

// HLF extent
Status HlfKvbStorage::SetHlfState(string key, string value) {
  com::vmware::concord::hlf::storage::HlfState proto;
  proto.set_version(KHlfStateStorageVersion);
  proto.set_state(value);

  size_t sersize = proto.ByteSize();
  uint8_t *ser = new uint8_t[sersize];
  proto.SerializeToArray(ser, sersize);
  put(HlfStateKey(key), Sliver(ser, sersize));
  return Status::OK();
}

////////////////////////////////////////
// READING

//  Get the number of the block that will be added when write_block is called.
uint64_t HlfKvbStorage::next_block_number() {
  // HLF block number equals KVB block number.
  return ro_storage_.getLastBlock() + 1;
}

//  Get the number of the most recent block that was added.
uint64_t HlfKvbStorage::current_block_number() {
  // HLF block number equals KVB block number.
  return ro_storage_.getLastBlock();
}

// HLF extent
string HlfKvbStorage::GetHlfState(const string &key) {
  uint64_t block_number = current_block_number();
  return GetHlfState(key, block_number);
}

string HlfKvbStorage::GetHlfState(const string &key, uint64_t &block_number) {
  Sliver kvbkey = HlfStateKey(key);
  Sliver value;
  BlockId out_block;

  // get concord's lastest block
  Status status = get(block_number, kvbkey, value, out_block);
  // convert proto binary to string
  LOG4CPLUS_DEBUG(logger_, "Getting HLF State by Key:"
                               << key << " looking block at: " << block_number
                               << " status: " << status << " key: " << kvbkey
                               << " value.length: " << value.length()
                               << " out block at: " << out_block);

  if (status.isOK() && value.length() > 0) {
    com::vmware::concord::hlf::storage::HlfState state;
    if (state.ParseFromArray(value.data(), value.length())) {
      if (state.version() == KHlfStateStorageVersion) {
        return state.state();
      } else {
        // should use other exception, like HLFException
        throw EVMException("Unknown hlf state version");
      }
    }
  }
  return "";
}

Status HlfKvbStorage::get(const Sliver &key, Sliver &value) {
  uint64_t block_number = current_block_number();
  BlockId out;
  return get(block_number, key, value, out);
}

Status HlfKvbStorage::get(const BlockId read_version, const Sliver &key,
                          Sliver &value, BlockId &out_block) {
  // TODO(BWF): this search will be very inefficient for a large set of changes
  for (auto &u : updates_) {
    if (u.first == key) {
      value = u.second;
      return Status::OK();
    }
  }
  // HLF block number equals KVB block number
  return ro_storage_.get(read_version, key, value, out_block);
}

}  // namespace hlf
}  // namespace concord
