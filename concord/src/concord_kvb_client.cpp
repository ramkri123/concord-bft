// Copyright 2018 VMware, all rights reserved
//
// Layer between api_connection and Blockchain::IClient
//
// This is the end of the client side of Concord. Commands sent from here will
// end up at KVBCommandsHandler.

#include <log4cplus/loggingmacros.h>
#include <boost/thread.hpp>

#include "concord_kvb_client.hpp"
#include "kvb/BlockchainInterfaces.h"

using namespace com::vmware::concord;

/**
 * Send a request to the replicas. Returns true if the response contains
 * something to forward (either a response message or an appropriate error
 * message). Returns false if the response is empty (for example, if parsing
 * failed).
 */
bool com::vmware::concord::KVBClient::send_request_sync(ConcordRequest &req,
                                                        bool isReadOnly,
                                                        ConcordResponse &resp) {
  std::string command;
  req.SerializeToString(&command);
  memset(m_outBuffer, 0, OUT_BUFFER_SIZE);

  uint32_t actualReplySize = 0;
  Blockchain::Status status = client_->invokeCommandSynch(
      command.c_str(), command.size(), isReadOnly, OUT_BUFFER_SIZE, m_outBuffer,
      &actualReplySize);

  if (status.isOK() && actualReplySize) {
    return resp.ParseFromArray(m_outBuffer, actualReplySize);
  } else {
    LOG4CPLUS_ERROR(logger_, "Error invoking "
                                 << (isReadOnly ? "read-only" : "read-write")
                                 << " command. Status: " << status
                                 << " Reply size: " << actualReplySize);
    ErrorResponse *err = resp.add_error_response();
    err->set_description("Internal concord Error");
    return true;
  }
}

com::vmware::concord::KVBClientPool::KVBClientPool(
    std::vector<KVBClient *> &clients)
    : logger_(
          log4cplus::Logger::getInstance("com.vmware.concord.KVBClientPool")),
      clients_(clients.size()) {
  for (auto it = clients.begin(); it < clients.end(); it++) {
    clients_.push(*it);
  }
}

com::vmware::concord::KVBClientPool::~KVBClientPool() {
  while (true) {
    KVBClient *client;
    if (!clients_.pop(client)) {
      LOG4CPLUS_INFO(logger_, "Client cleanup complete");
      break;
    }

    LOG4CPLUS_DEBUG(logger_, "Stopping and deleting client");
    delete client;
  }
}

bool com::vmware::concord::KVBClientPool::send_request_sync(
    ConcordRequest &req, bool isReadOnly, ConcordResponse &resp) {
  while (true) {
    KVBClient *client;
    if (!clients_.pop(client)) {
      boost::this_thread::yield();
      continue;
    }

    bool result = client->send_request_sync(req, isReadOnly, resp);
    clients_.push(client);
    return result;
  }
}