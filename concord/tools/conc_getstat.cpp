// Copyright 2018 VMware, all rights reserved
//
// Read contract storage from concord directly.

#include <iostream>
#include <inttypes.h>
#include <boost/program_options.hpp>
#include <google/protobuf/text_format.h>

#include "concmdex.hpp"
#include "concmdfmt.hpp"
#include "concmdopt.hpp"
#include "concmdconn.hpp"
#include "concord.pb.h"

using namespace boost::program_options;
using namespace com::vmware::concord;

#define OPT_CONTRACT "contract"
#define OPT_LOCATION "location"

void add_options(options_description &desc) {
   desc.add_options()
      (OPT_CONTRACT",c",
       value<std::string>(),
       "Address of the contract")
      (OPT_LOCATION",l",
       value<std::string>(),
       "Location in storage to read from");
}

// left padding with zeros
void pad(std::string &str, size_t width) {
   int total = width - str.size();
   if (total > 0) {
      str.insert(0, total, '\0');
   }
}

int main(int argc, char** argv)
{
   try {
      variables_map opts;
      if (!parse_options(argc, argv, &add_options, opts)) {
         return 0;
      }

      /*** Create request ***/

      ConcordRequest athReq;
      EthRequest *ethReq = athReq.add_eth_request();
      std::string address;
      std::string location;

      ethReq->set_method(EthRequest_EthMethod_GET_STORAGE_AT);

      if (opts.count(OPT_CONTRACT) > 0) {
         dehex0x(opts[OPT_CONTRACT].as<std::string>(), address);
	 ethReq->set_addr_to(address);
      } else {
         std::cerr << "Please provide a contract address." << std::endl;
      }

      if (opts.count(OPT_LOCATION) > 0) {
         dehex0x(opts[OPT_LOCATION].as<std::string>(), location);
      } else {
         // Many tests write the result to check to 0x0. Using it as the default
         // provides a convenient shortcut for debugging.
         std::cerr << "Warning: using default location: 0x0." << std::endl;
      }
      pad(location, 32);
      ethReq->set_data(location);

      std::string pbtext;
      google::protobuf::TextFormat::PrintToString(athReq, &pbtext);
      std::cout << "Message Prepared: " << pbtext << std::endl;

      /*** Send & Receive ***/

      ConcordResponse athResp;
      if (call_concord(opts, athReq, athResp)) {
         google::protobuf::TextFormat::PrintToString(athResp, &pbtext);
         std::cout << "Received response: " << pbtext << std::endl;

         /*** Handle Response ***/

         if (athResp.eth_response_size() == 1) {
            EthResponse ethResp = athResp.eth_response(0);
            if (ethResp.has_data()) {
               std::string result;
               hex0x(ethResp.data(), result);
               std::cout << "Data: " << result << std::endl;
            }
         } else {
            std::cerr << "Wrong number of eth_responses: "
                      << athResp.eth_response_size()
                      << " (expected 1)" << std::endl;
            return -1;
         }
      } else {
         return -1;
      }
   } catch (std::exception &e) {
      std::cerr << "Exception: " << e.what() << std::endl;
      return -1;
   }

   return 0;
}
