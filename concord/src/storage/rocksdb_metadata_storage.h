// Concord
//
// Copyright (c) 2019 VMware, Inc. All Rights Reserved.
//
// This product is licensed to you under the Apache 2.0 license (the
// "License").  You may not use this product except in compliance with the
// Apache 2.0 License.
//
// This product may include a number of subcomponents with separate copyright
// notices and license terms. Your use of these subcomponents is subject to the
// terms and conditions of the subcomponent's license, as noted in the LICENSE
// file.

#ifndef CONCORD_STORAGE_ROCKSDB_METADATA_STORAGE_H_
#define CONCORD_STORAGE_ROCKSDB_METADATA_STORAGE_H_

#ifdef USE_ROCKSDB

#include <log4cplus/loggingmacros.h>
#include <map>
#include <mutex>
#include "MetadataStorage.hpp"
#include "storage/database_interface.h"

namespace concord {
namespace storage {

typedef std::vector<uint32_t> ObjectIdsVector;
typedef std::map<uint32_t, size_t> ObjectIdToSizeMap;

class RocksDBMetadataStorage : public bftEngine::MetadataStorage {
 public:
  explicit RocksDBMetadataStorage(IDBClient *dbClient)
      : logger_(log4cplus::Logger::getInstance(
            "com.concord.vmware.metadatastorage")),
        dbClient_(dbClient) {
    objectIdToSizeMap_[objectsNumParamId_] = sizeof(objectsNum_);
  }

  bool initMaxSizeOfObjects(ObjectDesc *metadataObjectsArray,
                            uint32_t metadataObjectsArrayLength) override;
  void read(uint32_t objectId, uint32_t bufferSize, char *outBufferForObject,
            uint32_t &outActualObjectSize) override;
  void atomicWrite(uint32_t objectId, char *data, uint32_t dataLength) override;
  void beginAtomicWriteOnlyTransaction() override;
  void writeInTransaction(uint32_t objectId, char *data,
                          uint32_t dataLength) override;
  void commitAtomicWriteOnlyTransaction() override;
  concord::consensus::Status multiDel(const ObjectIdsVector &objectIds);
  bool isNewStorage() override;

 private:
  void verifyOperation(uint32_t objectId, uint32_t dataLen, const char *buffer,
                       bool writeOperation) const;

 private:
  const char *WRONG_FLOW =
      "beginAtomicWriteOnlyTransaction should be launched first";
  const char *WRONG_PARAMETER = "Wrong parameter value specified";

  const uint8_t objectsNumParamId_ = 1;

  log4cplus::Logger logger_;
  IDBClient *dbClient_ = nullptr;
  SetOfKeyValuePairs *transaction_ = nullptr;
  std::mutex ioMutex_;
  ObjectIdToSizeMap objectIdToSizeMap_;
  uint32_t objectsNum_ = 0;
};

}  // namespace storage
}  // namespace concord

#endif  // USE_ROCKSDB
#endif  // CONCORD_STORAGE_ROCKSDB_METADATA_STORAGE_H_
