// Copyright 2018 VMware, all rights reserved
//
// Athena node startup.

#include <iostream>
#include <csignal>
#include <boost/program_options.hpp>
#include <log4cplus/loggingmacros.h>
#include <log4cplus/configurator.h>
#include "common/utils.hpp"
#include "api_acceptor.hpp"
#include "athena_evm.hpp"
#include "configuration_manager.hpp"
#include "evm_init_params.hpp"
#include "kvb/DatabaseInterface.h"
#include "kvb/BlockchainDBAdapter.h"
#include "kvb/Comparators.h"
#include "kvb/InMemoryDBClient.h"
#ifdef USE_ROCKSDB
#include "kvb/RocksDBClient.h"
#endif

using namespace boost::program_options;
using boost::asio::ip::tcp;
using boost::asio::ip::address;
using boost::asio::io_service;
using log4cplus::Logger;

using namespace com::vmware::athena;
using namespace std;

// the Boost service hosting our Helen connections
static io_service *api_service;

void signalHandler(int signum) {
   try {
      Logger logger = Logger::getInstance("com.vmware.athena.main");
      LOG4CPLUS_INFO(logger, "Signal received (" << signum <<
                     "), stopping service");

      api_service->stop();
   } catch (exception &e) {
      cout << "Exception in signal handler: " << e.what() << endl;
   }
}

Blockchain::IDBClient* open_database(variables_map &opts, Logger logger)
{
   if (opts.count("blockchain_db_impl") < 1) {
      LOG4CPLUS_FATAL(logger, "Missing blockchain_db_impl config");
      throw new EVMException("Missing blockchain_db_impl config");
   }

   string db_impl_name = opts["blockchain_db_impl"].as<std::string>();
   if (db_impl_name == "memory") {
      LOG4CPLUS_INFO(logger, "Using memory blockchain database");
      return new Blockchain::InMemoryDBClient(
         (Blockchain::IDBClient::KeyComparator)&Blockchain::InMemKeyComp);
#ifdef USE_ROCKSDB
   } else if (db_impl_name == "rocksdb") {
      LOG4CPLUS_INFO(logger, "Using rocksdb blockchain database");
      string rocks_path = opts["blockchain_db_path"].as<std::string>();
      return new Blockchain::RocksDBClient(
         rocks_path,
         (Blockchain::IDBClient::KeyComparator)
         &Blockchain::RocksKeyComparator);
#endif
   } else {
      LOG4CPLUS_FATAL(logger, "Unknown blockchain_db_impl " << db_impl_name);
      throw new EVMException("Unknown blockchain_db_impl");
   }
}

/*
 * Start the service that listens for connections from Helen.
 */
int
run_service(variables_map &opts, Logger logger)
{
   EVMInitParams params;

   try {
      // If genesis block option was provided then read that so
      // it can be passed during EVM creation
      if (opts.count("genesis_block")) {
         string genesis_file_path = opts["genesis_block"].as<std::string>();
         LOG4CPLUS_INFO(logger, "Reading genesis block from " <<
                        genesis_file_path);
         params = EVMInitParams(genesis_file_path);
      } else {
         LOG4CPLUS_WARN(logger, "No genesis block provided");
      }

      Blockchain::IDBClient *dbclient = open_database(opts, logger);
      Blockchain::BlockchainDBAdapter db(dbclient);

      // throws an exception if it fails
      EVM athevm(params, db);

      std::string ip = opts["ip"].as<std::string>();
      short port = opts["port"].as<short>();

      api_service = new io_service();
      tcp::endpoint endpoint(address::from_string(ip), port);
      api_acceptor acceptor(*api_service, endpoint, athevm);

      signal(SIGINT, signalHandler);

      LOG4CPLUS_INFO(logger, "Listening on " << endpoint);
      api_service->run();

      //TODO(BWF): close dbclient gracefully

   } catch (EVMInitParamException &ex) {
      LOG4CPLUS_FATAL(logger, ex.what());
      return -1;
   } catch (EVMException &ex) {
      LOG4CPLUS_FATAL(logger, ex.what());
      return -1;
   }

   return 0;
}

int
main(int argc, char** argv)
{
   bool loggerInitialized = true;
   int result = 0;

   try {
      // Note that this must be the very first statement
      // in main function before doing any operations on config
      // parameters or 'argc/argv'. Never directly operate on
      // config parameters or command line parameters directly
      // always use po::variables_map interface for that.
      variables_map opts = initialize_config(argc, argv);

      if (opts.count("help"))
         return result;

      // Initialize logger
      log4cplus::initialize();
      log4cplus::ConfigureAndWatchThread
         configureThread(opts["logger_config"].as<string>(),
                         opts["logger_reconfig_time"].as<int>());
      loggerInitialized = true;

      // say hello
      Logger mainLogger = Logger::getInstance("com.vmware.athena.main");
      LOG4CPLUS_INFO(mainLogger, "VMware Project Athena starting");


      // actually run the service - when this call returns, the
      // service has shutdown
      result = run_service(opts, mainLogger);

      LOG4CPLUS_INFO(mainLogger, "VMware Project Athena halting");
   } catch (const error &ex) {
      if (loggerInitialized) {
         Logger mainLogger = Logger::getInstance("com.vmware.athena.main");
         LOG4CPLUS_FATAL(mainLogger, ex.what());
      } else {
         std::cerr << ex.what() << std::endl;
      }
      result = -1;
   }

   if (loggerInitialized) {
      Logger mainLogger = Logger::getInstance("com.vmware.athena.main");
      LOG4CPLUS_INFO(mainLogger, "Shutting down");
   }

   // cleanup required for properties-watching thread
   log4cplus::Logger::shutdown();

   return result;
}
