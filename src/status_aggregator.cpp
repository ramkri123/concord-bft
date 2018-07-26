// Copyright 2018 VMware, all rights reserved

#include <unordered_map>
#include "status_aggregator.hpp"
#include <memory>
#include <queue>
#include <mutex>
#include <thread>
#include <boost/asio.hpp>
#include <boost/thread/thread.hpp>

using namespace std;
using namespace com::vmware::athena;
using namespace boost;
using namespace boost::asio;

struct EnumHash
{
   template <typename T>
   size_t operator()(T t) const
   {
      return static_cast<size_t>(t);
   }
};

class StatusAggregator::Impl
{
private:
   /**
    * internal structure -Maps PeerInfoType to actual status.
    * currently only last status is saved
    * can be easily expanded to keep vector<BasePeerStatus>
   */
   typedef unordered_map<PeerInfoType, BasePeerStatus*, EnumHash> STAT_MAP;
   typedef STAT_MAP * STAT_MAP_PTR;

   /**
    * main data struct, map of maps
    * maps NodeId (from SBFT) to the map of its statuses (see above)
    * the idea is that UI asks for nodes info or specific node info so it can be
    * easily accessed using this map and internal maps
   */
   typedef unordered_map<int64_t, STAT_MAP_PTR> PEER_STAT_MAP;
   typedef PEER_STAT_MAP * PEER_STAT_MAP_PTR;

private:
   PEER_STAT_MAP_PTR _pPeerStatusMap = nullptr;
   std::mutex _inQueueMutex;

   /**
    * basic thread pool using boost::asio
    * the pool will handle all requests from the low level modules
    * to update stats in internal structs
   */
   std::shared_ptr<io_service> _pIoService = nullptr;
   std::shared_ptr<io_service::work> _pWork = nullptr;
   thread_group _threadPool;

   const int16_t POOL_SIZE = 1;
   const int32_t _peerFailThesholdMilli = 60000;
   const std::string HOSTNAME_PREFIX = "replica";
   const std::string PEER_STATE_READY = "ready";
   const std::string PEER_STATE_LIVE = "live";
   const int64_t TIME_NO_VALUE = -1;

   void
   update_connectivity_internal(PeerConnectivityStatus pcs)
   {
      std::lock_guard<std::mutex> lock(_inQueueMutex);
      auto it = _pPeerStatusMap->find(pcs.peerId);
      if (_pPeerStatusMap->end() != it) {
         auto status = it->second->find(PeerInfoType::Connectivity);
         if (status != it->second->end()) {
            auto st = static_cast<PeerConnectivityStatus *>(status->second);

            if(StatusType::Started != pcs.statusType) {
               (*st).statusTime = pcs.statusTime;
            }

            (*st).statusType = pcs.statusType;
         } else {
            auto st = new PeerConnectivityStatus(pcs);
            it->second->insert({PeerInfoType::Connectivity, st});
         }
      } else {
         auto pStatMap = new STAT_MAP();
         auto st = new PeerConnectivityStatus(pcs);
         pStatMap->insert({PeerInfoType::Connectivity, st});
         _pPeerStatusMap->insert({pcs.peerId, pStatMap});
      }
   }
public:
   Impl()
   {
      _pPeerStatusMap = new PEER_STAT_MAP();

      _pIoService = std::shared_ptr<io_service>(new io_service());
      _pWork =
              std::shared_ptr<io_service::work>(
                      new io_service::work(*_pIoService));

      for (auto i = 0; i < POOL_SIZE; i++) {
         _threadPool.create_thread(boost::bind(&io_service::run, _pIoService));
      }
   }

   ~Impl()
   {
      if(_pWork) {
         _pWork.reset();
      }

      _threadPool.join_all();

      if(_pIoService) {
         _pIoService->stop();
      }

      if (_pPeerStatusMap) {
         for ( auto it=_pPeerStatusMap->begin();
               it != _pPeerStatusMap->end();
               it++) {
            if (it->second) {
               delete it->second;
            }
         }

         delete _pPeerStatusMap;
      }
   }

   /**
    * this will post the task to thread pool asynchronously
    */
   void
   update_connectivity_async(PeerConnectivityStatus pcs)
   {
      ///  TODO (IG): patch to fix time. Currently SBFT uses internal time
      ///  SBFT today uses internal time that may not reflect epoch millis
      if (StatusType::Started != pcs.statusType) {
         pcs.statusTime = get_epoch_millis();
      } else {
         pcs.statusTime = TIME_NO_VALUE;
      }

      _pIoService->post(
              boost::bind(
                 &Impl::update_connectivity_internal,
                 this,
                 pcs));
   }

   vector<UiPeerInfo>
   get_peers_info()
   {
      std::lock_guard<std::mutex> lock(_inQueueMutex);
      vector<UiPeerInfo> res;
      for (auto it = _pPeerStatusMap->begin();
           it != _pPeerStatusMap->end();
           it++) {
         auto infoMapIt = it->second->find(PeerInfoType::Connectivity);
         if (infoMapIt != it->second->end()) {
            auto stPtr = static_cast<PeerConnectivityStatus *>(infoMapIt
                    ->second);
            UiPeerInfo pi;
            pi.failThresholdMilli = _peerFailThesholdMilli;
            pi.adress = stPtr->peerIp + ":" + to_string(stPtr->peerPort);
            pi.hostName = HOSTNAME_PREFIX + to_string(stPtr->peerId);

            if(StatusType::Started != stPtr->statusType) {
               pi.timeFromLastMessageMilli =
                       get_epoch_millis() - stPtr->statusTime;
               pi.state = PEER_STATE_LIVE;
            } else {
               pi.timeFromLastMessageMilli = TIME_NO_VALUE;
               pi.state = PEER_STATE_READY;
            }

            res.push_back(pi);
         }
      }

      return res;
   }
};

StatusAggregator::StatusAggregator() : _pImpl(new Impl())
{
}

UPDATE_CONNECTIVITY_FN
StatusAggregator::get_update_connectivity_fn()
{
   namespace pl = std::placeholders;
   return std::bind(
             &StatusAggregator::Impl::update_connectivity_async,
             _pImpl,
             pl::_1);
}

vector<UiPeerInfo>
StatusAggregator::get_peers_info()
{
   auto res = _pImpl->get_peers_info();
   return res;
}