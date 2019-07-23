// Copyright 2019 VMware, all rights reserved
//
// Shim between generic KVB and Concord-specific commands handlers.

#ifndef CONSENSUS_CONCORD_COMMANDS_HANDLER_HPP_
#define CONSENSUS_CONCORD_COMMANDS_HANDLER_HPP_

#include "concord.pb.h"
#include "storage/blockchain_interfaces.h"
#include "storage/concord_metadata_storage.h"
#include "time/time_contract.hpp"
#include "time/time_reading.hpp"

#include <log4cplus/logger.h>

namespace concord {
namespace consensus {

class ConcordCommandsHandler : public concord::storage::ICommandsHandler,
                               public concord::storage::IBlocksAppender {
 private:
  log4cplus::Logger logger_;
  concord::storage::ConcordMetadataStorage metadata_storage_;
  uint64_t executing_bft_sequence_num_;

 protected:
  const concord::storage::ILocalKeyValueStorageReadOnly &storage_;

 public:
  concord::storage::IBlocksAppender &appender_;
  std::unique_ptr<concord::time::TimeContract> time_;

  com::vmware::concord::ConcordRequest request_;
  com::vmware::concord::ConcordResponse response_;

 public:
  ConcordCommandsHandler(
      const concord::config::ConcordConfiguration &config,
      const concord::storage::ILocalKeyValueStorageReadOnly &storage,
      concord::storage::IBlocksAppender &appender);
  virtual ~ConcordCommandsHandler() {}

  // Callback from the replica via concord::storage::ICommandsHandler.
  int execute(uint16_t client_id, uint64_t sequence_num, bool read_only,
              uint32_t request_size, const char *request,
              uint32_t max_reply_size, char *out_reply,
              uint32_t &out_reply_size) override;

  // Our concord::storage::IBlocksAppender implementation, where we can add
  // lower-level data like time contract status, before forwarding to the true
  // appender.
  Status addBlock(const concord::storage::SetOfKeyValuePairs &updates,
                  concord::storage::BlockId &out_block_id) override;

  // Functions the subclass must implement are below here.

  // The up-call to execute a command. This base class's execute function calls
  // this Execute function after decoding the request buffer.
  //
  // `timeContract` will only be a valid pointer if time server is enabled. It
  // will be nullptr otherwise.
  //
  // The subclass should fill out any fields in `response` that it wants to
  // return to the client.
  virtual bool Execute(const com::vmware::concord::ConcordRequest &request,
                       bool read_only,
                       concord::time::TimeContract *time_contract,
                       com::vmware::concord::ConcordResponse &response) = 0;

  // In some cases, commands may arrive that require writing a KVB block to
  // store state that is not controlled by the subclass. This callback gives the
  // subclass a chance to add its own data to that block (for example, an
  // "empty" smart-contract-level block).
  virtual void WriteEmptyBlock(concord::time::TimeContract *time_contract) = 0;
};

}  // namespace consensus
}  // namespace concord

#endif  // CONSENSUS_CONCORD_COMMANDS_HANDLER_HPP_
