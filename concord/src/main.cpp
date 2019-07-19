// Copyright 2018-2019 VMware, all rights reserved
//
// Concord node startup.

#include <grpcpp/grpcpp.h>
#include <log4cplus/configurator.h>
#include <log4cplus/loggingmacros.h>
#include <boost/program_options.hpp>
#include <boost/thread.hpp>
#include <csignal>
#include <iostream>
#include <string>
#include <thread>
#include "api/api_acceptor.hpp"
#include "common/concord_exception.hpp"
#include "common/status_aggregator.hpp"
#include "config/configuration_manager.hpp"
#include "consensus/bft_configuration.hpp"
#include "consensus/client_imp.h"
#include "consensus/replica_imp.h"
#include "consensus/replica_state_sync_imp.hpp"
#include "daml/blocking_queue.h"
#include "daml/cmd_handler.hpp"
#include "daml/grpc_services.hpp"
#include "daml_commit.grpc.pb.h"
#include "daml_events.grpc.pb.h"
#include "ethereum/concord_evm.hpp"
#include "ethereum/eth_kvb_commands_handler.hpp"
#include "ethereum/eth_kvb_storage.hpp"
#include "ethereum/evm_init_params.hpp"
#include "hlf/grpc_services.hpp"
#include "hlf/kvb_commands_handler.hpp"
#include "hlf/kvb_storage.hpp"
#include "storage/blockchain_db_adapter.h"
#include "storage/blockchain_interfaces.h"
#include "storage/comparators.h"
#include "storage/database_interface.h"
#include "storage/in_memory_db_client.h"
#include "storage/rocksdb_client.h"
#include "time/time_pusher.hpp"
#include "time/time_reading.hpp"
#include "utils/concord_eth_sign.hpp"

using namespace boost::program_options;
using namespace std;

using boost::asio::io_service;
using boost::asio::ip::address;
using boost::asio::ip::tcp;
using log4cplus::Logger;

using concord::api::ApiAcceptor;
using concord::common::EthLog;
using concord::common::EthTransaction;
using concord::common::EVMException;
using concord::common::StatusAggregator;
using concord::common::zero_address;
using concord::common::zero_hash;
using concord::config::ConcordConfiguration;
using concord::ethereum::EthKvbStorage;
using concord::common::operator<<;
using concord::consensus::KVBClient;
using concord::consensus::KVBClientPool;
using concord::consensus::ReplicaImp;
using concord::ethereum::EthKvbCommandsHandler;
using concord::ethereum::EVM;
using concord::ethereum::EVMInitParams;
using concord::storage::BlockchainDBAdapter;
using concord::storage::BlockId;
using concord::storage::ClientConsensusConfig;
using concord::storage::CommConfig;
using concord::storage::IBlocksAppender;
using concord::storage::IClient;
using concord::storage::ICommandsHandler;
using concord::storage::IDBClient;
using concord::storage::ILocalKeyValueStorageReadOnly;
using concord::storage::InMemoryDBClient;
using concord::storage::IReplica;
using concord::storage::ReplicaConsensusConfig;
using concord::storage::RocksDBClient;
using concord::storage::RocksKeyComparator;
using concord::storage::SetOfKeyValuePairs;

using concord::hlf::ChaincodeInvoker;
using concord::hlf::HlfKvbCommandsHandler;
using concord::hlf::HlfKvbStorage;
using concord::hlf::RunHlfGrpcServer;

using concord::time::TimePusher;
using concord::utils::EthSign;

using com::digitalasset::kvbc::CommittedTx;
using concord::daml::BlockingPersistentQueue;
using concord::daml::CommitServiceImpl;
using concord::daml::DataServiceImpl;
using concord::daml::EventsServiceImpl;
using concord::daml::KVBCCommandsHandler;

// Parse BFT configuration
using concord::consensus::initializeSBFTConfiguration;

static unique_ptr<grpc::Server> daml_grpc_server = nullptr;
// the Boost service hosting our Helen connections
static io_service *api_service = nullptr;
static boost::thread_group worker_pool;

void signalHandler(int signum) {
  try {
    Logger logger = Logger::getInstance("com.vmware.concord.main");
    LOG4CPLUS_INFO(logger,
                   "Signal received (" << signum << "), stopping API service");

    if (api_service) {
      api_service->stop();
    }
    if (daml_grpc_server) {
      daml_grpc_server->Shutdown();
    }
  } catch (exception &e) {
    cout << "Exception in signal handler: " << e.what() << endl;
  }
}

unique_ptr<IDBClient> open_database(ConcordConfiguration &nodeConfig,
                                    Logger logger) {
  if (!nodeConfig.hasValue<std::string>("blockchain_db_impl")) {
    LOG4CPLUS_FATAL(logger, "Missing blockchain_db_impl config");
    throw EVMException("Missing blockchain_db_impl config");
  }

  string db_impl_name = nodeConfig.getValue<std::string>("blockchain_db_impl");
  if (db_impl_name == "memory") {
    LOG4CPLUS_INFO(logger, "Using memory blockchain database");
    return unique_ptr<IDBClient>(new InMemoryDBClient(
        (IDBClient::KeyComparator)&RocksKeyComparator::InMemKeyComp));
#ifdef USE_ROCKSDB
  } else if (db_impl_name == "rocksdb") {
    LOG4CPLUS_INFO(logger, "Using rocksdb blockchain database");
    string rocks_path = nodeConfig.getValue<std::string>("blockchain_db_path");
    return unique_ptr<IDBClient>(
        new RocksDBClient(rocks_path, new RocksKeyComparator()));
#endif
  } else {
    LOG4CPLUS_FATAL(logger, "Unknown blockchain_db_impl " << db_impl_name);
    throw EVMException("Unknown blockchain_db_impl");
  }
}

/**
 * IdleBlockAppender is a shim to wrap IReplica::addBlocktoIdleReplica in an
 * IBlocksAppender interface, so that it can be rewrapped in a EthKvbStorage
 * object, thus allowing the create_genesis_block function to use the same
 * functions as concord_evm to put data in the genesis block.
 */
class IdleBlockAppender : public IBlocksAppender {
 private:
  IReplica *replica_;

 public:
  IdleBlockAppender(IReplica *replica) : replica_(replica) {}

  concord::consensus::Status addBlock(const SetOfKeyValuePairs &updates,
                                      BlockId &outBlockId) override {
    outBlockId = 0;  // genesis only!
    return replica_->addBlockToIdleReplica(updates);
  }
};

/**
 * Create the initial transactions and a genesis block based on the
 * genesis file.
 */
concord::consensus::Status create_genesis_block(IReplica *replica,
                                                EVMInitParams params,
                                                Logger logger) {
  const ILocalKeyValueStorageReadOnly &storage = replica->getReadOnlyStorage();
  IdleBlockAppender blockAppender(replica);
  EthKvbStorage kvbStorage(storage, &blockAppender, 0);

  if (storage.getLastBlock() > 0) {
    LOG4CPLUS_INFO(logger, "Blocks already loaded, skipping genesis");
    return concord::consensus::Status::OK();
  }

  std::map<evm_address, evm_uint256be> genesis_acts =
      params.get_initial_accounts();
  uint64_t nonce = 0;
  uint64_t chainID = params.get_chainID();
  for (auto it = genesis_acts.begin(); it != genesis_acts.end(); ++it) {
    // store a transaction for each initial balance in the genesis block
    // defintition
    EthTransaction tx = {
        nonce,                   // nonce
        zero_hash,               // block_hash: will be set in write_block
        0,                       // block_number
        zero_address,            // from
        it->first,               // to
        zero_address,            // contract_address
        std::vector<uint8_t>(),  // input
        EVM_SUCCESS,             // status
        it->second,              // value
        0,                       // gas_price
        0,                       // gas_limit
        0,                       // gas_used
        std::vector<concord::common::EthLog>(),  // logs
        zero_hash,          // sig_r (no signature for genesis)
        zero_hash,          // sig_s (no signature for genesis)
        (chainID * 2 + 35)  // sig_v
    };
    evm_uint256be txhash = tx.hash();
    LOG4CPLUS_INFO(logger, "Created genesis transaction "
                               << txhash << " to address " << it->first
                               << " with value = " << tx.value);
    kvbStorage.add_transaction(tx);

    // also set the balance record
    kvbStorage.set_balance(it->first, it->second);
    nonce++;
  }
  kvbStorage.set_nonce(zero_address, nonce);

  uint64_t timestamp = params.get_timestamp();
  uint64_t gasLimit = params.get_gas_limit();

  // Genesis is always proposed and accepted at the same time.
  return kvbStorage.write_block(timestamp, gasLimit);
}

/*
 * Starts a set of worker threads which will call api_service.run() method.
 * This will allow us to have multiple threads accepting tcp connections
 * and passing the requests to KVBClient.
 */
void start_worker_threads(int number) {
  Logger logger = Logger::getInstance("com.vmware.concord.main");
  LOG4CPLUS_INFO(logger, "Starting " << number << " new API worker threads");
  assert(api_service);
  for (int i = 0; i < number; i++) {
    boost::thread *t = new boost::thread(
        boost::bind(&boost::asio::io_service::run, api_service));
    worker_pool.add_thread(t);
  }
}

unique_ptr<grpc::Server> RunDamlGrpcServer(
    std::string server_address, KVBClientPool &pool,
    const ILocalKeyValueStorageReadOnly *ro_storage,
    BlockingPersistentQueue<CommittedTx> &committedTxs) {
  DataServiceImpl *dataService = new DataServiceImpl(pool, ro_storage);
  CommitServiceImpl *commitService = new CommitServiceImpl(pool);
  EventsServiceImpl *eventsService = new EventsServiceImpl(committedTxs);

  grpc::ServerBuilder builder;
  builder.AddListeningPort(server_address, grpc::InsecureServerCredentials());
  builder.RegisterService(dataService);
  builder.RegisterService(commitService);
  builder.RegisterService(eventsService);

  // Finally assemble the server.
  return unique_ptr<grpc::Server>(builder.BuildAndStart());
}

/*
 * Start the service that listens for connections from Helen.
 */
int run_service(ConcordConfiguration &config, ConcordConfiguration &nodeConfig,
                Logger &logger) {
  unique_ptr<EVM> concevm;
  unique_ptr<EthSign> ethVerifier;
  EVMInitParams params;
  uint64_t chainID;
  BlockingPersistentQueue<CommittedTx> committedTxs;
  bool daml_enabled = config.getValue<bool>("daml_enable");

  bool hlf_enabled = config.getValue<bool>("hlf_enable");

  try {
    if (!daml_enabled && !hlf_enabled) {
      // The genesis parsing is Eth specific.
      if (nodeConfig.hasValue<std::string>("genesis_block")) {
        string genesis_file_path =
            nodeConfig.getValue<std::string>("genesis_block");
        LOG4CPLUS_INFO(logger,
                       "Reading genesis block from " << genesis_file_path);
        params = EVMInitParams(genesis_file_path);
        chainID = params.get_chainID();
        // throws an exception if it fails
        concevm = unique_ptr<EVM>(new EVM(params));
        ethVerifier = unique_ptr<EthSign>(new EthSign());
      } else {
        LOG4CPLUS_WARN(logger, "No genesis block provided");
      }
    }

    // Replica and communication config
    CommConfig commConfig;
    StatusAggregator sag;
    commConfig.statusCallback = sag.get_update_connectivity_fn();
    ReplicaConsensusConfig replicaConsensusConfig;

    // TODO(IG): check return value and shutdown concord if false
    initializeSBFTConfiguration(config, nodeConfig, &commConfig, nullptr, 0,
                                &replicaConsensusConfig);

    auto db_client = open_database(nodeConfig, logger);
    BlockchainDBAdapter db_adapter(db_client.get());

    // Replica
    //
    // TODO(IG): since ReplicaImpl is used as an implementation of few
    // intefaces, this object will be used for constructing
    // EthKvbCommandsHandler and thus we cant use IReplica here. Need to
    // restructure the code, to split interfaces implementation and to construct
    // objects in more clear way
    concord::consensus::ReplicaStateSyncImp replicaStateSync;
    ReplicaImp replica(commConfig, replicaConsensusConfig, &db_adapter,
                       replicaStateSync);

    unique_ptr<ICommandsHandler> kvb_commands_handler;
    if (daml_enabled) {
      std::string damle_addr{
          nodeConfig.getValue<std::string>("daml_execution_engine_addr")};
      kvb_commands_handler =
          unique_ptr<ICommandsHandler>(new KVBCCommandsHandler(
              &replica, &replica, committedTxs, damle_addr));
    } else if (hlf_enabled) {
      LOG4CPLUS_INFO(logger, "Hyperledger Fabric feature is enabled");
      // Init chaincode invoker
      ChaincodeInvoker *chaincode_invoker = new ChaincodeInvoker(nodeConfig);

      kvb_commands_handler =
          unique_ptr<ICommandsHandler>(new HlfKvbCommandsHandler(
              chaincode_invoker, config, nodeConfig, &replica, &replica));
    } else {
      kvb_commands_handler =
          unique_ptr<ICommandsHandler>(new EthKvbCommandsHandler(
              *concevm, *ethVerifier, config, nodeConfig, replica, replica));
      // Genesis must be added before the replica is started.
      concord::consensus::Status genesis_status =
          create_genesis_block(&replica, params, logger);
      if (!genesis_status.isOK()) {
        LOG4CPLUS_FATAL(logger,
                        "Unable to load genesis block: " << genesis_status);
        throw EVMException("Unable to load genesis block");
      }
    }

    replica.set_command_handler(kvb_commands_handler.get());
    replica.start();

    // Clients

    std::shared_ptr<TimePusher> timePusher;
    if (concord::time::IsTimeServiceEnabled(config)) {
      timePusher.reset(new TimePusher(config, nodeConfig));
    }

    std::vector<KVBClient *> clients;

    for (uint16_t i = 0;
         i < config.getValue<uint16_t>("client_proxies_per_replica"); ++i) {
      ClientConsensusConfig clientConsensusConfig;
      // TODO(IG): check return value and shutdown concord if false
      CommConfig clientCommConfig;
      initializeSBFTConfiguration(config, nodeConfig, &clientCommConfig,
                                  &clientConsensusConfig, i, nullptr);

      IClient *client = concord::consensus::createClient(clientCommConfig,
                                                         clientConsensusConfig);
      client->start();
      KVBClient *kvbClient = new KVBClient(client, timePusher);
      clients.push_back(kvbClient);
    }

    KVBClientPool pool(clients);

    if (timePusher) {
      timePusher->Start(&pool);
    }

    signal(SIGINT, signalHandler);

    // API server

    if (daml_enabled) {
      std::string daml_addr{
          nodeConfig.getValue<std::string>("daml_service_addr")};
      daml_grpc_server =
          RunDamlGrpcServer(daml_addr, pool, &replica, committedTxs);
      LOG4CPLUS_INFO(logger, "DAML grpc server listening on " << daml_addr);

      daml_grpc_server->Wait();
    } else if (hlf_enabled) {
      // Get listening address for services
      std::string key_value_service_addr =
          nodeConfig.getValue<std::string>("hlf_kv_service_address");
      std::string chaincode_service_addr =
          nodeConfig.getValue<std::string>("hlf_chaincode_service_address");

      // Create Hlf Kvb Storage instance for Hlf key value service
      // key value service could put updates to cache, but it is not allowed to
      // write block
      const ILocalKeyValueStorageReadOnly &storage = replica;
      IdleBlockAppender block_appender(&replica);
      HlfKvbStorage kvb_storage = HlfKvbStorage(storage, &block_appender, 0);

      // Start HLF gRPC services
      RunHlfGrpcServer(kvb_storage, pool, key_value_service_addr,
                       chaincode_service_addr);
    } else {
      std::string ip = nodeConfig.getValue<std::string>("service_host");
      short port = nodeConfig.getValue<short>("service_port");

      api_service = new io_service();
      tcp::endpoint endpoint(address::from_string(ip), port);
      uint64_t gasLimit = config.getValue<uint64_t>("gas_limit");
      ApiAcceptor acceptor(*api_service, endpoint, pool, sag, gasLimit,
                           chainID);
      LOG4CPLUS_INFO(logger, "API Listening on " << endpoint);

      start_worker_threads(nodeConfig.getValue<int>("api_worker_pool_size") -
                           1);

      // Wait for api_service->run() to return
      api_service->run();
      worker_pool.join_all();
    }

    if (timePusher) {
      timePusher->Stop();
    }

    replica.stop();
  } catch (std::exception &ex) {
    LOG4CPLUS_FATAL(logger, ex.what());
    return -1;
  }

  return 0;
}

int main(int argc, char **argv) {
  bool loggerInitialized = false;
  int result = 0;

  try {
    ConcordConfiguration config;

    // We initialize the logger to whatever log4cplus defaults to here so that
    // issues that arise while loading the configuration can be logged; the
    // log4cplus::ConfigureAndWatchThread for using and updating the requested
    // logger configuration file will be created once the configuration has been
    // loaded and we can read the path for this file from it.
    log4cplus::initialize();
    log4cplus::BasicConfigurator loggerInitConfig;
    loggerInitConfig.configure();

    // Note that this must be the very first statement
    // in main function before doing any operations on config
    // parameters or 'argc/argv'. Never directly operate on
    // config parameters or command line parameters directly
    // always use po::variables_map interface for that.
    variables_map opts = initialize_config(config, argc, argv);

    if (opts.count("help")) return result;

    if (opts.count("debug")) std::this_thread::sleep_for(chrono::seconds(20));

    // Get a reference to the node instance-specific configuration for the
    // current running Concord node because that is needed frequently and we do
    // not want to have to determine the current node every time.
    size_t nodeIndex = detectLocalNode(config);
    ConcordConfiguration &nodeConfig = config.subscope("node", nodeIndex);

    // Initialize logger
    log4cplus::ConfigureAndWatchThread configureThread(
        nodeConfig.getValue<std::string>("logger_config"),
        nodeConfig.getValue<int>("logger_reconfig_time"));
    loggerInitialized = true;

    // say hello
    Logger mainLogger = Logger::getInstance("com.vmware.concord.main");
    LOG4CPLUS_INFO(mainLogger, "VMware Project concord starting");

    // actually run the service - when this call returns, the
    // service has shutdown
    result = run_service(config, nodeConfig, mainLogger);

    LOG4CPLUS_INFO(mainLogger, "VMware Project concord halting");
  } catch (const error &ex) {
    if (loggerInitialized) {
      Logger mainLogger = Logger::getInstance("com.vmware.concord.main");
      LOG4CPLUS_FATAL(mainLogger, ex.what());
    } else {
      std::cerr << ex.what() << std::endl;
    }
    result = -1;
  }

  if (loggerInitialized) {
    Logger mainLogger = Logger::getInstance("com.vmware.concord.main");
    LOG4CPLUS_INFO(mainLogger, "Shutting down");
  }

  // cleanup required for properties-watching thread
  log4cplus::Logger::shutdown();

  return result;
}
