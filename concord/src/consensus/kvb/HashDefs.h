// Copyright 2018 VMware, all rights reserved
//
// Hash functions for our Sliver and KeyValuePair types.

#ifndef CONCORD_CONSENSUS_KVB_HASHDEFS_H_
#define CONCORD_CONSENSUS_KVB_HASHDEFS_H_

#include <stdlib.h>
#include "BlockchainInterfaces.h"
#include "sliver.hpp"

using concord::consensus::KeyValuePair;
using concord::consensus::Sliver;

// TODO(GG): do we want this hash function ? See also
// http://www.cse.yorku.ca/~oz/hash.html
inline size_t simpleHash(const uint8_t *data, const size_t len) {
  size_t hash = 5381;
  size_t t;

  for (size_t i = 0; i < len; i++) {
    t = data[i];
    hash = ((hash << 5) + hash) + t;
  }

  return hash;
}

namespace std {
template <>
struct hash<Sliver> {
  typedef Sliver argument_type;
  typedef std::size_t result_type;

  result_type operator()(const Sliver &t) const {
    return simpleHash(t.data(), t.length());
  }
};

template <>
struct hash<KeyValuePair> {
  typedef KeyValuePair argument_type;
  typedef std::size_t result_type;

  result_type operator()(const KeyValuePair &t) const {
    size_t keyHash = simpleHash(t.first.data(), t.first.length());
    return keyHash;
  }
};
}  // namespace std

#endif  // CONCORD_CONSENSUS_KVB_HASHDEFS_H_
