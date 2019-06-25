// Copyright 2019 VMware, all rights reserved

#include "time_reading.hpp"

#include <log4cplus/loggingmacros.h>
#include <chrono>

#include "config/configuration_manager.hpp"

using concord::config::ConcordConfiguration;
using std::chrono::system_clock;

namespace concord {
namespace time {

// Return true if the time service is enabled, or false if it is disabled.
bool IsTimeServiceEnabled(const ConcordConfiguration &config) {
  return config.getValue<bool>("FEATURE_time_service");
}

// Read milliseconds since the UNIX Epoch, according to the system clock.
//
// Eventually this should take a Config object, and use it to decide how to read
// the time.
uint64_t ReadTime() {
  system_clock::time_point now = system_clock::now();
  system_clock::duration since_epoch = now.time_since_epoch();
  return std::chrono::duration_cast<std::chrono::milliseconds>(since_epoch)
      .count();
}

}  // namespace time
}  // namespace concord
