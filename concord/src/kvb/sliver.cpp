// Copyright 2018 VMware, all rights reserved

/**
 * Blockchain::Sliver -- Zero-copy management of bytes.
 *
 * See sliver.hpp for design details.
 */

#include <algorithm>
#include <cassert>
#include <cstring>
#include <ios>
#include <memory>

#include "HexTools.h"
#include "sliver.hpp"

/**
 * Create an empty sliver.
 */
Blockchain::Sliver::Sliver() : m_data(nullptr), m_offset(0), m_length(0) {}

/**
 * Create a new sliver that will own the memory pointed to by `data`, which is
 * `length` bytes in size.
 *
 * Important: the `data` buffer should have been allocated with `new`, and not
 * `malloc`, because the shared pointer will use `delete` and not `free`.
 */
Blockchain::Sliver::Sliver(uint8_t* data, const size_t length)
    : m_data(data, std::default_delete<uint8_t[]>()),
      m_offset(0),
      m_length(length) {
  // Data must be non-null.
  assert(data);
}

/**
 * Create a sub-sliver that references a region of a base sliver.
 */
Blockchain::Sliver::Sliver(const Sliver& base, const size_t offset,
                           const size_t length)
    : m_data(base.m_data),
      // This sliver starts offset bytes from the offset of its base.
      m_offset(base.m_offset + offset),
      m_length(length) {
  // This sliver must start no later than the end of the base sliver.
  assert(offset <= base.m_length);
  // This sliver must end no later than the end of the base sliver.
  assert(length <= base.m_length - offset);
}

/**
 * Get the byte at `offset` in this sliver.
 */
uint8_t Blockchain::Sliver::operator[](const size_t offset) const {
  // This offset must be within this sliver.
  assert(offset < m_length);

  // The data for the requested offset is that many bytes after the offset from
  // the base sliver.
  return m_data.get()[m_offset + offset];
}

/**
 * Get a direct pointer to the data for this sliver. Remember that the Sliver
 * (or its base) still owns the data, so ensure that the lifetime of this Sliver
 * (or its base) is at least as long as the lifetime of the returned pointer.
 */
uint8_t* Blockchain::Sliver::data() const { return m_data.get() + m_offset; }

/**
 * Create a subsliver. Syntactic sugar for cases where a function call is more
 * natural than using the sub-sliver constructor directly.
 */
Blockchain::Sliver Blockchain::Sliver::subsliver(const size_t offset,
                                                 const size_t length) const {
  return Blockchain::Sliver(*this, offset, length);
}

size_t Blockchain::Sliver::length() const { return m_length; }

std::ostream& Blockchain::Sliver::operator<<(std::ostream& s) const {
  return hexPrint(s, data(), length());
}

std::ostream& Blockchain::operator<<(std::ostream& s,
                                     const Blockchain::Sliver& sliver) {
  return sliver.operator<<(s);
}

/**
 * Slivers are == if their lengths are the same, and each byte of their data is
 * the same.
 */
bool Blockchain::Sliver::operator==(const Sliver& other) const {
  // This could be just "compare(other) == 0", but the short-circuit of checking
  // lengths first can save us many cycles in some cases.
  return length() == other.length() &&
         memcmp(data(), other.data(), length()) == 0;
}

/**
 * a.compare(b) is:
 *  - 0 if lengths are the same, and bytes are the same
 *  - -1 if bytes are the same, but a is shorter
 *  - 1 if bytes are the same, but a is longer
 */
int Blockchain::Sliver::compare(const Sliver& other) const {
  int comp = memcmp(data(), other.data(), std::min(length(), other.length()));
  if (comp == 0) {
    if (length() < other.length()) {
      comp = -1;
    } else if (length() > other.length()) {
      comp = 1;
    }
  }
  return comp;
}

bool Blockchain::copyToAndAdvance(uint8_t* _buf, size_t* _offset,
                                  size_t _maxOffset, uint8_t* _src,
                                  size_t _srcSize) {
  if (!_buf) {
    return false;
  }

  if (!_offset) {
    return false;
  }

  if (!_src) {
    return false;
  }

  if (*_offset >= _maxOffset && _srcSize > 0) {
    return false;
  }

  memcpy(_buf + *_offset, _src, _srcSize);
  *_offset += _srcSize;

  return true;
}