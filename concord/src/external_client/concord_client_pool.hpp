// Concord
//
// Copyright (c) 2020 VMware, Inc. All Rights Reserved.
//
// This product is licensed to you under the Apache 2.0 license (the "License").
// You may not use this product except in compliance with the Apache 2.0
// License.
//
// This product may include a number of subcomponents with separate copyright
// notices and license terms. Your use of these subcomponents is subject to the
// terms and conditions of the sub-component's license, as noted in the LICENSE
// file.

#pragma once

#include <SimpleThreadPool.hpp>
#include <config/configuration_manager.hpp>
#include <mutex>
#include <queue>
#include "external_client.hpp"

namespace concord {
namespace external_client {
class ConcordClient;
}
namespace concord_client_pool {
// Represents the answer that the DAML Ledger API could get when sending a
// request
enum SubmitResult {
  Acknowledged,  // The request has been queued for submission
  Overloaded,    // There is no available client to the moment to process the
  // request
  InternalError  // An internal error has occurred. Reason is recorded in logs.
};

// Represents a Concord BFT client pool. The purpose of this class is to be easy
// to use for external users. This is achieved by:
//  * providing a simple public interface
//  * providing a generic public interface that allows for various use cases
//  * configuration via a file - users don't need to know what the structure of
//  the file is and changes to the file will not affect the interface of the
//  client
class ConcordClientPool {
 public:
  // Constructs the clients pool by passing absolute path to concord
  // configuration file.
  // Construction executes all needed steps to provide a ready-to-use
  // object (including starting internal threads, if needed).
  ConcordClientPool(std::string config_file_path);
  // constructor that gets the configuration on istream type,
  // helps on testing
  ConcordClientPool(std::istream& config_stream);

  ~ConcordClientPool();
  // This method is responsible to deal with requests in an asynchronous way,
  // for each request that comes, we will check if there is an available client
  // to deal with the problem if there is a client the request enters into a
  // thread pool and a positive answer is immediately returned to the
  // application. If there is no available client, a negative answer is returned
  // to the application.
  SubmitResult SendRequest(const void* request, std::uint32_t request_size,
                           bftEngine::ClientMsgFlag flags,
                           std::chrono::milliseconds timeout_ms,
                           std::uint32_t reply_size, void* out_reply,
                           std::uint32_t* out_actual_reply_size,
                           const std::string& correlation_id = {});

  void InsertClientToQueue(
      std::shared_ptr<concord::external_client::ConcordClient>& client);

 private:
  // Clients that are available for use (i.e. not already in use).
  std::queue<std::shared_ptr<external_client::ConcordClient>> clients_;
  // Thread pool, on each thread on client will run
  util::SimpleThreadPool jobs_thread_pool_;
  // Clients queue mutex
  std::mutex clients_queue_lock_;
};

class ConcordClientProcessingJob : public util::SimpleThreadPool::Job {
 public:
  ConcordClientProcessingJob(
      concord_client_pool::ConcordClientPool& clients,
      std::shared_ptr<external_client::ConcordClient> client,
      const void* request, std::uint32_t request_size,
      bftEngine::ClientMsgFlag flags, std::chrono::milliseconds timeout_ms,
      std::uint32_t reply_size, void* out_reply,
      std::uint32_t* out_actual_reply_size, const std::string& correlation_id)
      : clients_pool_{clients},
        processing_client_{std::move(client)},
        request_{request},
        request_size_{request_size},
        flags_{flags},
        timeout_ms_{timeout_ms},
        reply_size_{reply_size},
        out_reply_{out_reply},
        out_actual_reply_size_{out_actual_reply_size},
        correlation_id_{const_cast<std::string&>(correlation_id)} {};

  virtual ~ConcordClientProcessingJob() {}

  void release() override { delete this; }

  void execute() override;

 private:
  concord_client_pool::ConcordClientPool& clients_pool_;
  std::shared_ptr<external_client::ConcordClient> processing_client_;
  const void* request_;
  std::uint32_t request_size_;
  bftEngine::ClientMsgFlag flags_;
  std::chrono::milliseconds timeout_ms_;
  std::uint32_t reply_size_;
  void* out_reply_;
  std::uint32_t* out_actual_reply_size_;
  const std::string& correlation_id_;
};
}  // namespace concord_client_pool

}  // namespace concord