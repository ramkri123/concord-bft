// Copyright 2019 VMware, all rights reserved
//
// A state machine to provide a view of "real world" time to other state
// machines.
//
// Write commands to this state machine are tuples of (source, time) that update
// the last-read time at source. An update only modifies the source's recorded
// state if the time in the update is greater than the last time that source
// published.
//
// Read commands are requests for the aggregated "real world" time view. This
// aggregation is currently computed as the median of the most recent samples
// from all sources.

#ifndef TIME_TIME_CONTRACT_HPP

#include <map>
#include <utility>

#include "blockchain/kvb_storage.hpp"

namespace concord {
namespace time {

class TimeException : public std::exception {
 public:
  explicit TimeException(const std::string& what) : msg(what){};

  virtual const char* what() const noexcept override { return msg.c_str(); }

 private:
  std::string msg;
};

const int64_t time_storage_version = 1;

class TimeContract {
 public:
  TimeContract(concord::blockchain::KVBStorage& storage)
      : logger(log4cplus::Logger::getInstance("concord.time")),
        storage_(storage),
        samples_(nullptr){};

  ~TimeContract() {
    if (samples_) {
      delete samples_;
    }
  };

  uint64_t update(std::string& source, uint64_t time);
  uint64_t getTime();
  void store_latest_samples();

 private:
  log4cplus::Logger logger;
  concord::blockchain::KVBStorage& storage_;
  std::map<std::string, uint64_t>* samples_;

  void load_latest_samples();
  uint64_t summarize_time();
};

}  // namespace time
}  // namespace concord

#endif  // TIME_TIME_CONTRACT_HPP
