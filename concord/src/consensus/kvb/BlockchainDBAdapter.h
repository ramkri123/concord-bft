// Copyright 2018 VMware, all rights reserved
//
// Translation between BlockAppender/ILocalkeyValueStorage* to the underlying
// database.

#ifndef CONCORD_CONSENSUS_KVB_BLOCKCHAINDBADAPTER_H_
#define CONCORD_CONSENSUS_KVB_BLOCKCHAINDBADAPTER_H_

#include <log4cplus/loggingmacros.h>

#include "BlockchainDBTypes.hpp"
#include "BlockchainInterfaces.h"
#include "DatabaseInterface.h"
#include "sliver.hpp"

namespace concord {
namespace consensus {

class BlockchainDBAdapter {
 public:
  explicit BlockchainDBAdapter(IDBClient *_db)
      : logger(log4cplus::Logger::getInstance("com.vmware.concord.kvb")),
        m_db(_db),
        m_isEnd(false) {}

  IDBClient *getDb() { return m_db; }

  Status addBlock(BlockId _blockId, Sliver _blockRaw);
  Status updateKey(Key _key, BlockId _block, Value _value);
  Status addBlockAndUpdateMultiKey(const SetOfKeyValuePairs &_kvMap,
                                   BlockId _block, Sliver _blockRaw);
  Status getKeyByReadVersion(BlockId readVersion, Sliver key, Sliver &outValue,
                             BlockId &outBlock) const;
  Status getBlockById(BlockId _blockId, Sliver &_blockRaw, bool &_found) const;

  IDBClient::IDBClientIterator *getIterator() { return m_db->getIterator(); }

  Status freeIterator(IDBClient::IDBClientIterator *_iter) {
    return m_db->freeIterator(_iter);
  }

  Status first(IDBClient::IDBClientIterator *iter, BlockId readVersion,
               OUT BlockId &actualVersion, OUT bool &isEnd, OUT Sliver &_key,
               OUT Sliver &_value);
  Status seekAtLeast(IDBClient::IDBClientIterator *iter, Sliver _searchKey,
                     BlockId _readVersion, OUT BlockId &_actualVersion,
                     OUT Sliver &_key, OUT Sliver &_value, OUT bool &_isEnd);
  Status next(IDBClient::IDBClientIterator *iter, BlockId _readVersion,
              OUT Sliver &_key, OUT Sliver &_value, OUT BlockId &_actualVersion,
              OUT bool &_isEnd);

  Status getCurrent(IDBClient::IDBClientIterator *iter, OUT Sliver &_key,
                    OUT Sliver &_value);
  Status isEnd(IDBClient::IDBClientIterator *iter, OUT bool &_isEnd);

  Status delKey(Sliver _key, BlockId _blockID);
  Status delBlock(BlockId _blockId);
  void deleteBlockAndItsKeys(BlockId blockId);

  void monitor() const;

  BlockId getLatestBlock();
  BlockId getLastReachableBlock();

 private:
  log4cplus::Logger logger;
  IDBClient *m_db;
  KeyValuePair m_current;
  bool m_isEnd;
};

class KeyManipulator {
 public:
  static Sliver genDbKey(EDBKeyType _type, Key _key, BlockId _blockId);
  static Sliver genBlockDbKey(BlockId _blockId);
  static Sliver genDataDbKey(Key _key, BlockId _blockId);
  static char extractTypeFromKey(Key _key);
  static BlockId extractBlockIdFromKey(const log4cplus::Logger &logger,
                                       Key _key);
  static ObjectId extractObjectIdFromKey(const log4cplus::Logger &logger,
                                         Key _key);
  static Sliver extractKeyFromKeyComposedWithBlockId(
      const log4cplus::Logger &logger, Key _composedKey);
  static KeyValuePair composedToSimple(const log4cplus::Logger &logger,
                                       KeyValuePair _p);
  static Sliver generateMetadataKey(ObjectId objectId);
};

}  // namespace consensus
}  // namespace concord

#endif  // CONCORD_CONSENSUS_KVB_BLOCKCHAINDBADAPTER_H_
