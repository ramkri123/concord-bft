// Copyright 2018 VMware, all rights reserved
//
// concord common Utilities.
#include <stdexcept>

#include "utils.hpp"

using namespace std;
using boost::multiprecision::uint256_t;

char hexval(char c) {
  if (c >= '0' && c <= '9') {
    return c - '0';
  } else if (c >= 'a' && c <= 'f') {
    return 10 + c - 'a';
  } else if (c >= 'A' && c <= 'F') {
    return 10 + c - 'A';
  } else {
    throw invalid_argument("non-hex character");
  }
}

/**
   Converts the given string into a vector of uint8_t
   Every pair of consecutive character is considered as a
   hex number and then that is converted into a uint8_t
   For example. `ABCD` will convert to vector {171, 205}
*/
vector<uint8_t> com::vmware::concord::dehex(const std::string &str) {
  if (str.size() % 2 != 0) {
    throw invalid_argument("nibble missing in string");
  }
  // allow people to include "0x" prefix, or not
  size_t adjust = (str[0] == '0' && str[1] == 'x') ? 2 : 0;
  size_t binsize = (str.size() - adjust) / 2;

  vector<uint8_t> ret;

  for (size_t i = 0; i < binsize; i++) {
    ret.push_back((hexval(str[i * 2 + adjust]) << 4) |
                  hexval(str[i * 2 + adjust + 1]));
  }
  return ret;
}

/** Converts the given uint64_t into a evm_uint256be type
    The top 24 bytes are always going to be 0 in this conversion
*/
void com::vmware::concord::to_evm_uint256be(uint64_t val, evm_uint256be *ret) {
  uint8_t mask = 0xff;
  for (size_t i = 0; i < sizeof(evm_uint256be); i++) {
    uint8_t byte = val & mask;
    ret->bytes[sizeof(evm_uint256be) - i - 1] = byte;  // big endian order
    val = val >> 8;
  }
}

/** Converts the given evm_uint256be into a uint64_t, if the value of
    @val is more than 2^64 then return value will simply contain the
    lower 8 bytes of @val
*/
uint64_t com::vmware::concord::from_evm_uint256be(const evm_uint256be *val) {
  const size_t offset = sizeof(evm_uint256be) - sizeof(uint64_t);
  uint64_t ret = 0;
  for (size_t i = 0; i < sizeof(uint64_t); i++) {
    ret = ret << 8;
    ret |= val->bytes[i + offset];
  }
  return ret;
}

int64_t com::vmware::concord::get_epoch_millis() {
  using namespace std::chrono;
  int64_t res =
      duration_cast<milliseconds>(system_clock::now().time_since_epoch())
          .count();
  return res;
}

uint256_t com::vmware::concord::to_uint256_t(const evm_uint256be *val) {
  uint256_t out{0};
  assert(val != nullptr);
  std::vector<uint8_t> val_v(val->bytes, val->bytes + sizeof(evm_uint256be));
  import_bits(out, val_v.begin(), val_v.end());
  return out;
}

evm_uint256be com::vmware::concord::from_uint256_t(const uint256_t *val) {
  evm_uint256be out{0};
  std::vector<uint8_t> val_v;
  assert(val != nullptr);
  export_bits(*val, std::back_inserter(val_v), 8);
  while (val_v.size() < sizeof(evm_uint256be)) {
    val_v.insert(val_v.begin(), 0);
  }
  memcpy(out.bytes, val_v.data(), val_v.size());
  return out;
}
