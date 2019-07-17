// Copyright 2019 VMware, all rights reserved

#include "grpc_services.hpp"

#include <string>

#include "daml/cmd_handler.hpp"
#include "storage/blockchain_db_types.h"

using com::digitalasset::kvbc::Command;
using com::digitalasset::kvbc::CommandReply;
using com::digitalasset::kvbc::CommitRequest;
using com::digitalasset::kvbc::CommitResponse;
using com::digitalasset::kvbc::CommitResponse_CommitStatus;
using com::digitalasset::kvbc::CommitResponse_CommitStatus_OK;
using com::digitalasset::kvbc::CommittedTx;
using com::digitalasset::kvbc::CommittedTxsRequest;
using com::digitalasset::kvbc::GetLatestBlockIdRequest;
using com::digitalasset::kvbc::ReadTransactionRequest;
using com::digitalasset::kvbc::ReadTransactionResponse;
using com::vmware::concord::ConcordRequest;
using com::vmware::concord::ConcordResponse;
using com::vmware::concord::DamlRequest;
using com::vmware::concord::DamlResponse;
using grpc::ServerContext;
using grpc::ServerWriter;

using concord::storage::IClient;
using concord::storage::ILocalKeyValueStorageReadOnly;
using concord::storage::Key;
using concord::storage::Value;

using namespace std;

namespace concord {
namespace daml {

grpc::Status DataServiceImpl::GetLatestBlockId(
    ServerContext* context, const GetLatestBlockIdRequest* request,
    com::digitalasset::kvbc::BlockId* reply) {
  reply->set_block_id(ro_storage_->getLastBlock());
  return grpc::Status::OK;
}

grpc::Status DataServiceImpl::ReadTransaction(
    ServerContext* context, const ReadTransactionRequest* request,
    ReadTransactionResponse* reply) {
  LOG4CPLUS_INFO(logger, "DataService: ReadTransaction...");

  concord::storage::BlockId readBlockId = request->block_id();
  if (readBlockId <= 0) {
    readBlockId = ro_storage_->getLastBlock();
  }

  for (int i = 0; i < request->keys_size(); i++) {
    const string& keyStr = request->keys(i);
    Key key = CreateSliver(keyStr);
    Value value;
    concord::storage::BlockId outBlockId;
    concord::consensus::Status status =
        ro_storage_->get(readBlockId, key, value, outBlockId);
    if (status.isOK()) {
      com::digitalasset::kvbc::KeyValuePair* kv = reply->add_results();
      kv->set_key(keyStr);
      kv->set_value(value.data(), value.length());
    } else {
      LOG4CPLUS_ERROR(logger, "DataService: key '"
                                  << keyStr << "' was not found! " << status);
    }
  }
  // FIXME(JM): Return block ids of each separate get, or return max block id?
  reply->set_block_id(readBlockId);
  return grpc::Status::OK;
}

grpc::Status CommitServiceImpl::CommitTransaction(ServerContext* context,
                                                  const CommitRequest* request,
                                                  CommitResponse* reply) {
  LOG4CPLUS_INFO(logger, "CommitService: Transactions...");

  ConcordResponse resp;
  ConcordRequest req;
  DamlRequest* daml_request = req.mutable_daml_request();

  Command cmd;
  cmd.mutable_commit()->CopyFrom(*request);

  std::string cmd_string;
  cmd.SerializeToString(&cmd_string);
  daml_request->set_command(cmd_string.c_str(), cmd_string.size());

  if (!pool.send_request_sync(req, false /* read-only */, resp)) {
    LOG4CPLUS_ERROR(logger, "DAML commit transaction failed");
    return grpc::Status::CANCELLED;
  }

  assert(resp.has_daml_response());
  assert(resp.daml_response().has_command_reply());

  CommandReply cmd_reply;
  if (!cmd_reply.ParseFromString(resp.daml_response().command_reply())) {
    LOG4CPLUS_ERROR(logger, "Failed to parse DAML/CommandReply");
    return grpc::Status::CANCELLED;
  }
  assert(cmd_reply.has_commit());

  reply->CopyFrom(cmd_reply.commit());
  return grpc::Status::OK;
}

grpc::Status EventsServiceImpl::CommittedTxs(
    ServerContext* context, const CommittedTxsRequest* request,
    ServerWriter<CommittedTx>* writer) {
  LOG4CPLUS_INFO(logger, "EventsService: CommittedTxs...");

  BlockingPersistentQueueReader<CommittedTx> reader =
      committed_txs_.newReader(0);

  while (1) {
    CommittedTx committed_tx = reader.pop();
    LOG4CPLUS_INFO(logger, "KVBCEventsService: Sending event for blockId "
                               << committed_tx.block_id());

    if (!writer->Write(committed_tx)) {
      break;
    }
  }
  return grpc::Status::OK;
}

}  // namespace daml
}  // namespace concord
