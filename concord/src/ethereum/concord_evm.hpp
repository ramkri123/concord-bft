// Copyright 2018 VMware, all rights reserved
//
// Concord Ethereum VM management.

#ifndef ETHEREUM_CONCORD_EVM_HPP
#define ETHEREUM_CONCORD_EVM_HPP

#include <log4cplus/loggingmacros.h>
#include <map>
#include <memory>
#include <vector>
#include "blockchain/kvb_storage.hpp"
#include "common/concord_types.hpp"
#include "consensus/kvb/BlockchainInterfaces.h"
#include "evm.h"
#include "evm_init_params.hpp"
#include "utils/concord_utils.hpp"

namespace concord {
namespace ethereum {

// forward declaration for callbacks.
class EVM;

/**
 * This extern block of ath_* functions are callbacks that the EVM uses to
 * interact with our state-keeping layer.
 */
extern "C" {
/**
 * Our wrapper around EVM's wrapper, where we can add pointers to the modules
 * we're using to keep state.
 */
typedef struct concord_context {
  /** evmctx must be first, so we can cast to our wrapper */
  struct evm_context evmctx;
  class EVM* ath_object;
  class concord::blockchain::KVBStorage* kvbStorage;
  std::vector<::concord::common::EthLog>* evmLogs;
  log4cplus::Logger* logger;
  uint64_t timestamp;

  // Stash to answer ORIGIN opcode. This starts with the same value as
  // evm_message.sender, but sender changes as contracts call other contracts,
  // while origin always points to the same address.
  struct evm_address origin;

  // Which contract we're actually using for storage. This is usually the
  // contract being called, but may be the contract doing the calling during
  // CALLCODE and DELEGATECALL.
  struct evm_address storage_contract;
} concord_context;

EVM* ath_object(const struct evm_context* evmctx);
const concord_context* ath_context(const struct evm_context* evmctx);

int ath_account_exists(struct evm_context* evmctx,
                       const struct evm_address* address);
void ath_get_storage(struct evm_uint256be* result, struct evm_context* evmctx,
                     const struct evm_address* address,
                     const struct evm_uint256be* key);
void ath_set_storage(struct evm_context* evmctx,
                     const struct evm_address* address,
                     const struct evm_uint256be* key,
                     const struct evm_uint256be* value);
void ath_get_balance(struct evm_uint256be* result, struct evm_context* evmctx,
                     const struct evm_address* address);
size_t ath_get_code_size(struct evm_context* evmctx,
                         const struct evm_address* address);
size_t ath_get_code(const uint8_t** result_code, struct evm_context* evmctx,
                    const struct evm_address* address);
void ath_selfdestruct(struct evm_context* evmctx,
                      const struct evm_address* address,
                      const struct evm_address* beneficiary);
void ath_emit_log(struct evm_context* evmctx, const struct evm_address* address,
                  const uint8_t* data, size_t data_size,
                  const struct evm_uint256be topics[], size_t topics_count);
void ath_call(struct evm_result* result, struct evm_context* evmctx,
              const struct evm_message* msg);
void ath_get_block_hash(struct evm_uint256be* result,
                        struct evm_context* evmctx, int64_t number);
void ath_get_tx_context(struct evm_tx_context* result,
                        struct evm_context* evmctx);

/*
 * Function dispatch table for EVM. Specified by EEI.
 */
const static struct evm_context_fn_table concord_fn_table = {
    ath_account_exists, ath_get_storage,    ath_set_storage,  ath_get_balance,
    ath_get_code_size,  ath_get_code,       ath_selfdestruct, ath_call,
    ath_get_tx_context, ath_get_block_hash, ath_emit_log};
}

class EVM {
 public:
  explicit EVM(EVMInitParams params);
  ~EVM();

  /* Concord API */
  void transfer_fund(evm_message& message,
                     concord::blockchain::KVBStorage& kvbStorage,
                     evm_result& result);
  evm_result run(evm_message& message, uint64_t timestamp,
                 concord::blockchain::KVBStorage& kvbStorage,
                 std::vector<::concord::common::EthLog>& evmLogs,
                 const evm_address& origin,
                 const evm_address& storage_contract);
  evm_result create(evm_address& contract_address, evm_message& message,
                    uint64_t timestamp,
                    concord::blockchain::KVBStorage& kvbStorage,
                    std::vector<::concord::common::EthLog>& evmLogs,
                    const evm_address& origin);
  evm_address contract_destination(evm_address& sender, uint64_t nonce) const;

 private:
  evm_instance* evminst;
  log4cplus::Logger logger;

  // chain to which we are connected
  uint64_t chainId;

  evm_result execute(evm_message& message, uint64_t timestamp,
                     concord::blockchain::KVBStorage& kvbStorage,
                     std::vector<::concord::common::EthLog>& evmLogs,
                     const std::vector<uint8_t>& code,
                     const evm_address& origin,
                     const evm_address& storage_contract);
};

}  // namespace ethereum
}  // namespace concord

#endif  // ETHEREUM_CONCORD_EVM_HPP