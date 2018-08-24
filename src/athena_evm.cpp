// Copyright 2018 VMware, all rights reserved
//
// Athena Ethereum VM management.

#include <iostream>
#include <cstring>
#include <stdexcept>
#include <memory>
#include <log4cplus/loggingmacros.h>

#include "athena_evm.hpp"
#include "athena_exception.hpp"
#include "athena_kvb_storage.hpp"
#include "athena_log.hpp"
#include "athena_types.hpp"
#include "common/rlp.hpp"
#include "common/athena_eth_hash.hpp"
#include "kvb/BlockchainInterfaces.h"
#include "kvb/HashDefs.h"
#include "kvb/slice.h"
#include "kvb/HexTools.h"

#ifdef USE_HERA
#include "hera.h"
#else
#include "evmjit.h"
#endif

using namespace com::vmware::athena;
using log4cplus::Logger;

/**
 * Initialize the athena/evm context and start the evm instance.
 */
com::vmware::athena::EVM::EVM(EVMInitParams params)
   : logger(Logger::getInstance("com.vmware.athena.evm")),
     chainId(params.get_chainID())
{
#ifdef USE_HERA
   evminst = hera_create();
#else
   evminst = evmjit_create();
#endif

   if (!evminst) {
      LOG4CPLUS_FATAL(logger, "Could not create EVM instance");
      throw EVMException("Could not create EVM instance");
   }
   LOG4CPLUS_INFO(logger, "EVM started");
}

/**
 * Shutdown the EVM instance and destroy the athena context.
 */
com::vmware::athena::EVM::~EVM()
{
   evminst->destroy(evminst);
   LOG4CPLUS_INFO(logger, "EVM stopped");
}

/**
 * Run a contract, or just transfer value if the destination is not a
 * contract. Calling a contract can either be done with 'call' method or with
 * 'sendTransaction'. Generally pure methods (methods which don't change any
 * state) are called via 'call' method and all others are called via
 * 'sendTransaction' method. The 'sendTransaction' way requires that the
 * transaction is recorded. However for 'call' way there is no transaction to
 * record, it is a simple read storage operation.
 */
evm_result com::vmware::athena::EVM::run(evm_message &message,
                                         KVBStorage &kvbStorage)
{
   assert(message.kind != EVM_CREATE);

   std::vector<uint8_t> code;
   evm_uint256be hash;
   evm_result result;
   if (kvbStorage.get_code(message.destination, code, hash)) {
      LOG4CPLUS_DEBUG(logger, "Loaded code from " << message.destination);
      message.code_hash = hash;

      try {
         result = execute(message, kvbStorage, code);
      } catch (ReadOnlyModeException rome) {
         LOG4CPLUS_DEBUG(logger,
                         "Non-pure contract function called "
                         "with read-only storage. Contract: "
                         << message.destination);
         result.status_code = EVM_FAILURE;
      } catch (EVMException e) {
         LOG4CPLUS_ERROR(logger,
                         "EVM execution exception: '" << e.what() << "'. "
                         << "Contract: " << message.destination);
         result.status_code = EVM_FAILURE;
      }
   } else if (message.input_size == 0) {
      LOG4CPLUS_DEBUG(logger, "No code found at " << message.destination);
      memset(&result, 0, sizeof(result));

      if (!kvbStorage.is_read_only()) {
         uint64_t transfer_val = from_evm_uint256be(&message.value);

         try {
            uint64_t sender_balance = kvbStorage.get_balance(message.sender);
            uint64_t destination_balance =
               kvbStorage.get_balance(message.destination);

            // Don't allow if source account does not exist.
            if (!kvbStorage.account_exists(message.sender)) {
               result.status_code = EVM_FAILURE;
               LOG4CPLUS_INFO(logger, "Source account with address "
                              << message.sender << ", does not exist.");
            }

            // Don't allow if source account has insufficient balance.
            else if (sender_balance < transfer_val) {
               result.status_code = EVM_FAILURE;
               LOG4CPLUS_INFO(logger,
                              "Account with address " << message.sender <<
                              ", does not have sufficient funds (" <<
                              sender_balance << ").");
            }

            else {
               kvbStorage.set_balance(message.destination,
                                      destination_balance += transfer_val);
               kvbStorage.set_balance(message.sender,
                                      sender_balance -= transfer_val);
               result.status_code = EVM_SUCCESS;
               LOG4CPLUS_DEBUG(logger, "Transferred  " << transfer_val <<
                               " units to: " << message.destination <<
                               " from: " << message.sender);
            }
         } catch (...) {
            LOG4CPLUS_DEBUG(logger, "Failed to decode balances");
            result.status_code = EVM_FAILURE;
         }
      } else {
         LOG4CPLUS_DEBUG(logger,
                         "Balance transfer attempted in read-only mode.");
         result.status_code = EVM_FAILURE;
      }
   } else {
      LOG4CPLUS_DEBUG(logger, "Input data, but no code at " <<
                      message.destination << ", returning error code.");
      // attempted to run a contract that doesn't exist
      memset(&result, 0, sizeof(result));
      result.status_code = EVM_FAILURE;
   }

   return result;
}

/**
 * Create a contract.
 */
evm_result com::vmware::athena::EVM::create(evm_address &contract_address,
                                            evm_message &message,
                                            KVBStorage &kvbStorage)
{
   assert(message.kind == EVM_CREATE);
   assert(message.input_size > 0);

   std::vector<uint8_t> code;
   evm_uint256be hash;
   evm_result result;
   if (!kvbStorage.get_code(contract_address, code, hash)) {
      LOG4CPLUS_DEBUG(logger, "Creating contract at " << contract_address);

      std::vector<uint8_t> create_code =
         std::vector<uint8_t>(message.input_data,
                              message.input_data+message.input_size);
      message.destination = contract_address;

      // we need a hash for this, or evmjit will cache its compilation under
      // something random
      message.code_hash = EthHash::keccak_hash(create_code);

      result = execute(message, kvbStorage, create_code);

      // TODO: check if the new contract is zero bytes in length;
      //       return error, not success in that case
      if (result.status_code == EVM_SUCCESS) {
         LOG4CPLUS_DEBUG(logger, "Contract created at " << contract_address <<
                         " with " << result.output_size << "bytes of code.");
         kvbStorage.set_code(contract_address,
                             result.output_data,
                             result.output_size);


         // There is a bug (either in evmjit or in our usage of evm) which
         // causes nested contract creation calls to give segmentation
         // fault. This bug also causes segmentation fault if we try to call
         // release method on result object in a normal contract creation call.
         // The reason is that `evmjit` stores a pointer to its internal data
         // inside evm_result's optional data storage and when the evm_result
         // objects scope ends we (or evm in case of nested call) call release
         // method to free memory from that pointer. However, this optional data
         // storage actually uses evm_result structure's create_address field to
         // store that pointer, in case of nested contract creation we store the
         // address of created contract into this field and it over-writes the
         // already stored pointer. This leads to seg-fault when freeing that
         // memory.  To fix this temporarily we just call release on result
         // object ourselves. Ideally only the owner of result object should
         // call release method on it and the result object itself should not be
         // used once we call release on it but this works until we find a
         // proper way.
         if (result.release) {
            result.release(&result);
            result.release = nullptr;
         }

         result.create_address = contract_address;
      }
   } else {
      LOG4CPLUS_DEBUG(logger, "Existing code found at " <<
                      message.destination << ", returning error code.");
      // attempted to call a contract that doesn't exist
      memset(&result, 0, sizeof(result));
      result.status_code = EVM_FAILURE;
   }

   // don't expose the address if it wasn't used
   if (result.status_code != EVM_SUCCESS) {
      result.create_address = zero_address;
   }

   return result;
}

/**
 * Contract destination is the low 20 bytes of the SHA3 hash of the RLP encoding
 * of [sender_address, sender_nonce].
 */
evm_address com::vmware::athena::EVM::contract_destination(
   evm_address &sender, uint64_t nonce) const
{
   RLPBuilder rlpb;
   rlpb.start_list();

   // RLP building is done in reverse order - build flips it for us

   if (nonce == 0) {
      // "0" is encoded as "empty string" here, not "integer zero"
      std::vector<uint8_t> empty_nonce;
      rlpb.add(empty_nonce);
   } else {
      rlpb.add(nonce);
   }

   rlpb.add(sender);
   std::vector<uint8_t> rlp = rlpb.build();

   // hash it
   evm_uint256be hash = EthHash::keccak_hash(rlp);

   // the lower 20 bytes are the address
   evm_address address;
   std::copy(hash.bytes+(sizeof(evm_uint256be)-sizeof(evm_address)),
             hash.bytes+sizeof(evm_uint256be),
             address.bytes);
   return address;
}

/**
 * Creates a new user account with 0 balance.
 * Generates a Keccak256 hash of the passphrase provided by the
 * user and uses its last 20 bytes as the account address.
 */
bool com::vmware::athena::EVM::new_account(
   const std::string& passphrase,
   KVBStorage &kvbStorage,
   evm_address& address /* OUT */)
{
   std::vector<uint8_t> vec(passphrase.begin(), passphrase.end());
   evm_uint256be hash = EthHash::keccak_hash(vec);

   std::copy(hash.bytes+(sizeof(evm_uint256be)-sizeof(evm_address)),
             hash.bytes+sizeof(evm_uint256be),
             address.bytes);

   if(kvbStorage.account_exists(address)) {
      return false;
   } else {
      kvbStorage.set_balance(address, 0);
      // TODO: "zero-address?" notes below: personal_newAccount should be
      // handled entirely in Helen. The creation of the account does not need to
      // be recorded on the blockchain, except to appease athena_evm's existence
      // check before allowing a balance transfer. Checking that the destination
      // of a balance transfer exists should also be removed (see Ethereum
      // address 0's current balance for compatibility arguments).
      uint64_t nonce = kvbStorage.get_nonce(zero_address);
      EthTransaction tx = {
         nonce,                  // nonce: zero-address nonce?
         zero_hash,              // block_hash: will be set in write_block
         0,                      // block_number: will be set in write_block
         zero_address,           // from
         address,                // to
         zero_address,           // contract_address
         std::vector<uint8_t>(), // input
         EVM_SUCCESS,            // status
         0,                      // value
         0,                      // gas_price
         0,                      // gas_limit
         zero_hash,              // sig_r: zero-address signature?
         zero_hash,              // sig_s: zero-address signature?
         0                       // sig_v: zero-address signature? chainID?
      };
      kvbStorage.add_transaction(tx);
      kvbStorage.set_nonce(zero_address, nonce+1);
      kvbStorage.write_block();
      return true;
   }
}

evm_result com::vmware::athena::EVM::execute(evm_message &message,
                                             KVBStorage &kvbStorage,
                                             const std::vector<uint8_t> &code)
{
   // wrap an evm context in an athena context
   athena_context athctx = {{&athena_fn_table},
                            this,
                            &kvbStorage,
                            &logger};

   return evminst->execute(evminst, &athctx.evmctx, EVM_BYZANTIUM,
                           &message, &code[0], code.size());
}

extern "C" {
/**
 * The next several ath_* functions are callbacks that the EVM uses to interact
 * with our state-keeping layer.
 */

   EVM* ath_object(const struct evm_context* evmctx) {
      return reinterpret_cast<const athena_context*>(evmctx)->ath_object;
   }

   const athena_context* ath_context(const struct evm_context* evmctx) {
      return reinterpret_cast<const athena_context*>(evmctx);
   }

   int ath_account_exists(struct evm_context* evmctx,
                          const struct evm_address* address) {
      LOG4CPLUS_INFO(*(ath_context(evmctx)->logger),
                     "EVM::account_exists called, address: " << *address);

      if (ath_context(evmctx)->kvbStorage->account_exists(*address)) {
         return 1;
      }
      return 0;
   }

   void ath_get_storage(struct evm_uint256be* result,
                        struct evm_context* evmctx,
                        const struct evm_address* address,
                        const struct evm_uint256be* key) {
      LOG4CPLUS_DEBUG(*(ath_context(evmctx)->logger),
                      "EVM::get_storage called, address: " << *address <<
                      " key: " << *key);

      *result = ath_context(evmctx)->kvbStorage->get_storage(*address, *key);
   }

   void ath_set_storage(struct evm_context* evmctx,
                        const struct evm_address* address,
                        const struct evm_uint256be* key,
                        const struct evm_uint256be* value) {
      LOG4CPLUS_DEBUG(*(ath_context(evmctx)->logger),
                      "EVM::set_storage called, address: " << *address <<
                      " key: " << *key << " value: " << *value);

      ath_context(evmctx)->kvbStorage->set_storage(*address, *key, *value);
   }

   void ath_get_balance(struct evm_uint256be* result,
                        struct evm_context* evmctx,
                        const struct evm_address* address) {
      LOG4CPLUS_INFO(*(ath_context(evmctx)->logger),
                     "EVM::get_balance called, address: " << *address);

      try {
         to_evm_uint256be(
            ath_context(evmctx)->kvbStorage->get_balance(*address), result);
      } catch (...) {
         // if the account's balance couldn't be deserialized, it's safest to
         // return zero from here
         to_evm_uint256be(0, result);
      }
   }

   size_t ath_get_code_size(struct evm_context* evmctx,
                            const struct evm_address* address) {
      LOG4CPLUS_INFO(*(ath_context(evmctx)->logger),
                     "ath_get_code_size called, address: " << *address);
      std::vector<uint8_t> code;
      evm_uint256be hash;
      if (ath_context(evmctx)->kvbStorage->get_code(*address, code, hash)) {
         return code.size();
      }

      return 0;
   }

   size_t ath_get_code(const uint8_t** result_code,
                       struct evm_context* evmctx,
                       const struct evm_address* address) {
      LOG4CPLUS_INFO(*(ath_context(evmctx)->logger),
                     "ath_get_code called, address: " << *address);

      std::vector<uint8_t> stored_code;
      evm_uint256be hash;
      if (ath_context(evmctx)->kvbStorage->get_code(
             *address, stored_code, hash)) {
         if (result_code) {
            *result_code = (uint8_t*)malloc(stored_code.size());
            if (*result_code) {
               memcpy(result_code, &stored_code[0], stored_code.size());
            }
         }
         return stored_code.size();
      }
      return 0;
   }

   void ath_selfdestruct(struct evm_context* evmctx,
                         const struct evm_address* address,
                         const struct evm_address* beneficiary) {
      LOG4CPLUS_INFO(*(ath_context(evmctx)->logger),
                     "ath_selfdestruct called, address: " << *address <<
                     " beneficiary: " << *beneficiary);

      // TODO: Actually self-destruct contract.
   }

   void ath_emit_log(struct evm_context* evmctx,
                     const struct evm_address* address,
                     const uint8_t* data,
                     size_t data_size,
                     const struct evm_uint256be topics[],
                     size_t topics_count) {
      LOG4CPLUS_INFO(*(ath_context(evmctx)->logger),
                     "EVM::emit_log called, address: " << *address);

      // TODO: Actually log the message.
   }

   void ath_call(struct evm_result* result,
                 struct evm_context* evmctx,
                 const struct evm_message* msg) {
      // create copy of message struct since
      // call function needs non-const message object
      evm_message call_msg = *msg;

      LOG4CPLUS_DEBUG(*(ath_context(evmctx)->logger),
                      "EVM::call called: " << call_msg);

      // our block-creation scheme will get confused if the EVM isn't
      // incrementing the depth for us
      assert(msg->depth > 0);

      // evm_result object sent by evm is un-initialized, not initializing it
      // can cause segmentation errors
      memset(result, 0, sizeof(evm_result));

      if (msg->kind == EVM_CREATE) {
         KVBStorage *kvbStorage = ath_context(evmctx)->kvbStorage;

         uint64_t nonce = kvbStorage->get_nonce(call_msg.sender);
         kvbStorage->set_nonce(call_msg.sender, nonce+1);

         evm_address contract_address =
            ath_object(evmctx)->contract_destination(call_msg.sender, nonce);

         *result = ath_object(evmctx)->create(
            contract_address, call_msg, *kvbStorage);
      } else {
         *result = ath_object(evmctx)->run(
            call_msg, *(ath_context(evmctx)->kvbStorage));
      }
   }

   void ath_get_block_hash(struct evm_uint256be* result,
                           struct evm_context* evmctx,
                           int64_t number) {
      LOG4CPLUS_DEBUG(*(ath_context(evmctx)->logger),
                      "EVM::get_block_hash called, block: " << number);

      try {
         if (number < 0 ||
             (uint64_t)number >
	        ath_context(evmctx)->kvbStorage->current_block_number()) {
            // KVBlockchain internals assert that the value passed to get_block
            // is <= the latest block number
            *result = zero_hash;
         } else {
            EthBlock blk = ath_context(evmctx)->kvbStorage->get_block(number);
            *result = blk.hash;
         }
      } catch (...) {
         *result = zero_hash;
      }
   }

   void ath_get_tx_context(struct evm_tx_context* result,
                           struct evm_context* evmctx) {
      LOG4CPLUS_INFO(*(ath_context(evmctx)->logger),
                     "EVM::get_tx_context called");

      // TODO: Actually get the transaction context. For now, set to known
      // value. What is the "transaction context" anyway?
      memset(result, 0, sizeof(*result));
   }
}
