// Copyright 2018 VMware, all rights reserved
//
// Send a transaction to concord directly.

#include <google/protobuf/text_format.h>
#include <inttypes.h>
#include <boost/program_options.hpp>
#include <iostream>

#include "concmdconn.hpp"
#include "concmdex.hpp"
#include "concmdfmt.hpp"
#include "concmdopt.hpp"

#include "concord.pb.h"
#include "hlf_services.grpc.pb.h"

#include <grpc/support/log.h>
#include <grpcpp/grpcpp.h>

using namespace boost::program_options;
using namespace com::vmware::concord;

using com::vmware::concord::hlf::services::GrpcService;
using grpc::Channel;
using grpc::ClientContext;
using grpc::Status;

#define OPT_METHOD "method"
#define OPT_CHAINCODE "chaincode"
#define OPT_INPUT "input"
#define OPT_VERSION "version"

void add_options(options_description &desc) {
  desc.add_options()(OPT_METHOD ",m", value<std::string>(), "Chaincode method")(
      OPT_CHAINCODE ",c", value<std::string>(), "Chaincode name")(
      OPT_INPUT ",i", value<std::string>(), "Parameters for calling chaincode")(
      OPT_VERSION ",v", value<std::string>(), "Chaincode Version");
}

class ConcordClient {
 public:
  ConcordClient(std::shared_ptr<Channel> channel)
      : stub_(GrpcService::NewStub(channel)) {}

  grpc::Status TriggerChaincode(const ConcordRequest &concordRequest,
                                ConcordResponse &concordResponse) {
    ClientContext context;

    return stub_->TriggerChaincode(&context, concordRequest, &concordResponse);
  }

 private:
  std::unique_ptr<GrpcService::Stub> stub_;
};

int main(int argc, char *argv[]) {
  try {
    variables_map opts;
    if (!parse_options(argc, argv, &add_options, opts)) {
      return 0;
    }
    /*** init grpc client ***/
    std::string grpcServer;
    std::string addr = opts[OPT_ADDRESS].as<std::string>();
    std::string port = opts[OPT_PORT].as<std::string>();
    grpcServer = addr + ":" + port;

    ConcordClient concordClient(
        grpc::CreateChannel(grpcServer, grpc::InsecureChannelCredentials()));
    /*** create request ***/

    ConcordRequest athReq;
    HlfRequest *hlfReq = athReq.add_hlf_request();

    if (opts.count(OPT_METHOD) > 0) {
      HlfRequest_HlfMethod method;
      std::string type = opts[OPT_METHOD].as<std::string>();

      if (type == "install") {
        method = HlfRequest_HlfMethod_INSTALL;
      } else if (type == "instantiate") {
        method = HlfRequest_HlfMethod_INSTANTIATE;
      } else if (type == "upgrade") {
        method = HlfRequest_HlfMethod_UPGRADE;
      } else if (type == "invoke") {
        method = HlfRequest_HlfMethod_INVOKE;
      } else if (type == "query") {
        method = HlfRequest_HlfMethod_QUERY;
      } else {
        std::cout << "Unknown chaincode method" << std::endl;
        return 0;
      }

      hlfReq->set_method(method);

    } else {
      std::cout << "Need to provide chaincode method (install, instantiate, "
                   "query, invoke)"
                << std::endl;
      return 0;
    }

    if (opts.count(OPT_CHAINCODE) > 0) {
      hlfReq->set_chaincode_name(opts[OPT_CHAINCODE].as<std::string>());
    }

    if (opts.count(OPT_INPUT) > 0) {
      hlfReq->set_input(opts[OPT_INPUT].as<std::string>());
    }

    if (opts.count(OPT_VERSION) > 0) {
      hlfReq->set_version(opts[OPT_VERSION].as<std::string>());
    }

    // hard code my channel here
    hlfReq->set_chain_id("mychannel");

    std::string pbtext;
    google::protobuf::TextFormat::PrintToString(athReq, &pbtext);
    std::cout << "Message Prepared: " << pbtext << std::endl;

    /*** Send and Receive ***/

    ConcordResponse athResp;
    if (concordClient.TriggerChaincode(athReq, athResp).ok()) {
      google::protobuf::TextFormat::PrintToString(athResp, &pbtext);
      std::cout << "Received response: " << pbtext << std::endl;

      /*** Handle Response ***/

      if (athResp.hlf_response_size() == 1) {
        HlfResponse hlfResp = athResp.hlf_response(0);
        if (hlfResp.has_data()) {
          std::cout << "Transaction Receipt: " << hlfResp.data() << std::endl;
        } else {
          std::cerr << "HlfResponse has no data" << std::endl;
          return -1;
        }
      } else if (athResp.error_response_size() == 1) {
        ErrorResponse errorResp = athResp.error_response(0);
        if (errorResp.has_description()) {
          std::cout << "Error Response: " << errorResp.description()
                    << std::endl;
          return -1;
        } else {
          std::cout << "Error response had no description" << std::endl;
          return -1;
        }
      } else {
        std::cerr << "Wrong number of hlf_responses ("
                  << athResp.hlf_response_size() << ") or errors ("
                  << athResp.error_response_size() << ")"
                  << " (expected 1)" << std::endl;
        return -1;
      }
    } else {
      std::cout << "Failed to call gRPC service" << std::endl;
      return -1;
    }
  } catch (std::exception &e) {
    std::cerr << "Exception: " << e.what() << std::endl;
    return -1;
  }

  return 0;
}
