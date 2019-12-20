// Copyright 2019 VMware, all rights reserved

#include "pruning_serialization.hpp"

namespace concord {
namespace pruning {
namespace detail {

std::string& operator<<(
    std::string& buf, const com::vmware::concord::LatestPrunableBlock& block) {
  if (block.has_replica()) {
    buf << block.replica();
  }

  if (block.has_block_id()) {
    buf << block.block_id();
  }

  if (block.has_signature()) {
    buf += block.signature();
  }

  return buf;
}

}  // namespace detail
}  // namespace pruning
}  // namespace concord
