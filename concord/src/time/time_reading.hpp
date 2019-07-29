// Copyright 2019 VMware, all rights reserved
//
// Utilities for reading the current time at this host.

#ifndef TIME_TIME_READING_HPP
#define TIME_TIME_READING_HPP

#include <google/protobuf/timestamp.pb.h>
#include "config/configuration_manager.hpp"

namespace concord {
namespace time {

bool IsTimeServiceEnabled(const concord::config::ConcordConfiguration &config);

google::protobuf::Timestamp ReadTime();

}  // namespace time
}  // namespace concord

#endif  // TIME_TIME_READING_HPP
