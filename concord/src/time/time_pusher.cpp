// Copyright 2019 VMware, all rights reserved

#include "time_pusher.hpp"

#include <log4cplus/loggingmacros.h>
#include <chrono>
#include <mutex>
#include <thread>

#include "concord.pb.h"
#include "config/configuration_manager.hpp"
#include "consensus/kvb_client.hpp"
#include "time/time_reading.hpp"

using com::vmware::concord::ConcordRequest;
using com::vmware::concord::ConcordResponse;
using concord::time::TimePusher;

TimePusher::TimePusher(const concord::config::ConcordConfiguration &nodeConfig,
                       concord::consensus::KVBClientPool &clientPool)
    : logger_(log4cplus::Logger::getInstance("concord.time.pusher")),
      nodeConfig_(nodeConfig),
      clientPool_(clientPool),
      stop_(false) {
  if (nodeConfig.hasValue<int>("time_pusher_period_ms")) {
    periodMilliseconds_ = nodeConfig.getValue<int>("time_pusher_period_ms");
  } else {
    periodMilliseconds_ = 0;
  }
}

void TimePusher::Start() {
  if (!nodeConfig_.hasValue<std::string>("time_source_id")) {
    LOG4CPLUS_INFO(logger_,
                   "Not starting thead: no time_source_id configured.");
    return;
  }

  if (periodMilliseconds_ <= 0) {
    LOG4CPLUS_INFO(logger_, "Not starting thread: period is "
                                << periodMilliseconds_ << " ms (less than 1).");
    return;
  }

  std::lock_guard<std::mutex> lock(threadMutex_);
  if (pusherThread_.joinable()) {
    LOG4CPLUS_INFO(logger_, "Ignoring duplicate start request.");
    return;
  }

  pusherThread_ = std::thread(&TimePusher::ThreadFunction, this);
}

void TimePusher::Stop() {
  std::lock_guard<std::mutex> lock(threadMutex_);
  if (!pusherThread_.joinable()) {
    LOG4CPLUS_INFO(logger_, "Ignoring stop request - nothing to stop");
    return;
  }

  stop_ = true;
  pusherThread_.join();

  // allows the thread to be restarted, if we like
  stop_ = false;
}

void TimePusher::ThreadFunction() {
  LOG4CPLUS_INFO(
      logger_, "Thread started with period " << periodMilliseconds_ << " ms.");
  ConcordRequest req;
  ConcordResponse resp;

  while (!stop_) {
    std::this_thread::sleep_for(std::chrono::milliseconds(periodMilliseconds_));

    // TODO: check if we need to send an update first.

    try {
      AddTimeToCommand(nodeConfig_, req);
      clientPool_.send_request_sync(req, false /* not read-only */, resp);
      req.Clear();
      resp.Clear();
    } catch (...) {
      // We don't want this thread to die for any reason other than being shut
      // down, because we don't have anything monitoring to restart it if it
      // does. So we'll swallow all exceptions and just yell into the log about
      // any problems and wait for an admin to notice.
      LOG4CPLUS_ERROR(logger_, "Unable to send time update");
    }
  }
}