// Copyright 2018 VMware, all rights reserved
//
// Storage key comparators definition.

#ifndef CONCORD_CONSENSUS_COMPARATORS_H_
#define CONCORD_CONSENSUS_COMPARATORS_H_

#include <log4cplus/loggingmacros.h>

#ifdef USE_ROCKSDB
#include "rocksdb/comparator.h"
#include "rocksdb/slice.h"
#endif
#include "sliver.hpp"

namespace concord {
namespace consensus {
/*
 * Basic comparator. Decomposes storage key into parts (type, version,
 * application key).
 */

/* RocksDB */
#ifdef USE_ROCKSDB
class RocksKeyComparator : public rocksdb::Comparator {
 public:
  RocksKeyComparator()
      : logger(log4cplus::Logger::getInstance("com.vmware"
                                              ".concord.kvb")) {}
  int Compare(const rocksdb::Slice& _a, const rocksdb::Slice& _b) const;

  // GG: Ignore the following methods for now:
  const char* Name() const { return "RocksKeyComparator"; }
  void FindShortestSeparator(std::string*, const rocksdb::Slice&) const {}
  void FindShortSuccessor(std::string*) const {}
  static bool InMemKeyComp(const log4cplus::Logger& logger, const Sliver& _a,
                           const Sliver& _b);

 private:
  static int ComposedKeyComparison(const log4cplus::Logger& logger,
                                   const Sliver& _a, const Sliver& _b);

 private:
  log4cplus::Logger logger;
};
#endif

}  // namespace consensus
}  // namespace concord

#endif  // CONCORD_CONSENSUS_COMPARATORS_H_
