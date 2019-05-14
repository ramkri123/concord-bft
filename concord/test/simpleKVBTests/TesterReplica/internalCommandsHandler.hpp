// Concord
//
// Copyright (c) 2019 VMware, Inc. All Rights Reserved.
//
// This product is licensed to you under the Apache 2.0 license (the "License").
// You may not use this product except in compliance with the Apache 2.0
// License.
//
// This product may include a number of subcomponents with separate copyright
// notices and license terms. Your use of these subcomponents is subject to the
// terms and conditions of the subcomponent's license, as noted in the LICENSE
// file.

#ifndef INTERNAL_COMMANDS_HANDLER_HPP
#define INTERNAL_COMMANDS_HANDLER_HPP

#include "Logging.hpp"
#include "consensus/kvb/BlockchainInterfaces.h"
#include "simpleKVBTestsBuilder.hpp"

class InternalCommandsHandler : public Blockchain::ICommandsHandler {
 public:
  InternalCommandsHandler(Blockchain::ILocalKeyValueStorageReadOnly *storage,
                          Blockchain::IBlocksAppender *blocksAppender,
                          concordlogger::Logger &logger)
      : m_storage(storage),
        m_blocksAppender(blocksAppender),
        m_logger(logger) {}

  int execute(uint16_t clientId, uint64_t sequenceNum, bool readOnly,
              uint32_t requestSize, const char *request, uint32_t maxReplySize,
              char *outReply, uint32_t &outActualReplySize) override;

 private:
  bool executeWriteCommand(uint32_t requestSize, const char *request,
                           uint64_t sequenceNum, size_t maxReplySize,
                           char *outReply, uint32_t &outReplySize);

  bool executeReadOnlyCommand(uint32_t requestSize, const char *request,
                              size_t maxReplySize, char *outReply,
                              uint32_t &outReplySize);

  bool verifyWriteCommand(
      uint32_t requestSize,
      const BasicRandomTests::SimpleCondWriteRequest &request,
      size_t maxReplySize, uint32_t &outReplySize) const;

  bool executeReadCommand(uint32_t requestSize, const char *request,
                          size_t maxReplySize, char *outReply,
                          uint32_t &outReplySize);

  bool executeGetLastBlockCommand(uint32_t requestSize, size_t maxReplySize,
                                  char *outReply, uint32_t &outReplySize);

  void addMetadataKeyValue(Blockchain::SetOfKeyValuePairs &updates,
                           uint64_t sequenceNum) const;

 private:
  static Blockchain::Sliver buildSliverFromStaticBuf(char *buf);

 private:
  Blockchain::ILocalKeyValueStorageReadOnly *m_storage;
  Blockchain::IBlocksAppender *m_blocksAppender;
  concordlogger::Logger &m_logger;
  size_t m_readsCounter = 0;
  size_t m_writesCounter = 0;
  size_t m_getLastBlockCounter = 0;
};

#endif