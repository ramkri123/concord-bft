// Copyright 2019 VMware, all rights reserved

#ifndef CONCORD_DAML_GRPC_SERVICES_HPP_
#define CONCORD_DAML_GRPC_SERVICES_HPP_

#include <grpcpp/grpcpp.h>
#include <log4cplus/loggingmacros.h>

#include "consensus/kvb_client.hpp"
#include "daml/blocking_queue.h"
#include "daml_commit.grpc.pb.h"
#include "daml_data.grpc.pb.h"
#include "daml_events.grpc.pb.h"
#include "storage/blockchain_interfaces.h"

namespace concord {
namespace daml {

class DataServiceImpl final
    : public com::digitalasset::kvbc::DataService::Service {
 private:
  log4cplus::Logger logger;
  const concord::storage::ILocalKeyValueStorageReadOnly* ro_storage_;

 public:
  DataServiceImpl(concord::consensus::KVBClientPool& p,
                  const concord::storage::ILocalKeyValueStorageReadOnly* ro)
      : logger(log4cplus::Logger::getInstance("com.vmware.concord.daml")),
        ro_storage_(ro) {}

  grpc::Status GetLatestBlockId(
      grpc::ServerContext* context,
      const com::digitalasset::kvbc::GetLatestBlockIdRequest* request,
      com::digitalasset::kvbc::BlockId* reply) override;

  grpc::Status ReadTransaction(
      grpc::ServerContext* context,
      const com::digitalasset::kvbc::ReadTransactionRequest* request,
      com::digitalasset::kvbc::ReadTransactionResponse* reply) override;
};

class CommitServiceImpl final
    : public com::digitalasset::kvbc::CommitService::Service {
 private:
  log4cplus::Logger logger;
  concord::consensus::KVBClientPool& pool;
  std::mutex mutex;

 public:
  explicit CommitServiceImpl(concord::consensus::KVBClientPool& p)
      : logger(log4cplus::Logger::getInstance("com.vmware.concord.daml")),
        pool(p) {}

  grpc::Status CommitTransaction(
      grpc::ServerContext* context,
      const com::digitalasset::kvbc::CommitRequest* request,
      com::digitalasset::kvbc::CommitResponse* reply) override;
};

class EventsServiceImpl final
    : public com::digitalasset::kvbc::EventsService::Service {
 private:
  log4cplus::Logger logger;

 public:
  explicit EventsServiceImpl(
      BlockingPersistentQueue<com::digitalasset::kvbc::CommittedTx>&
          committedTxs)
      : logger(log4cplus::Logger::getInstance("com.vmware.concord.daml")),
        committedTxs_(committedTxs) {}

  grpc::Status CommittedTxs(
      grpc::ServerContext* context,
      const com::digitalasset::kvbc::CommittedTxsRequest* request,
      grpc::ServerWriter<com::digitalasset::kvbc::CommittedTx>* writer);

  BlockingPersistentQueue<com::digitalasset::kvbc::CommittedTx>& committedTxs_;
};

}  // namespace daml
}  // namespace concord

#endif  // CONCORD_DAML_GRPC_SERVICES_HPP_
