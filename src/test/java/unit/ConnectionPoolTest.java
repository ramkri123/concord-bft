package unit;

import java.io.IOException;

import base.BaseTest;
import connections.AthenaConnectionFactory;
import connections.AthenaConnectionFactory.ConnectionType;
import connections.AthenaConnectionPool;
import connections.IAthenaConnection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ConnectionPoolTest extends BaseTest {
   private AthenaConnectionPool _pool;
   private static Logger _log = LogManager.getLogger(ConnectionPoolTest.class);

   public ConnectionPoolTest() throws IOException {
      super(ConnectionPoolTest.class);
      _pool = AthenaConnectionPool.getInstance();

   }

   protected void setUp() throws IOException {
      _log.info("setUp");
      _pool.initialize(_conf,
                       new AthenaConnectionFactory(ConnectionType.Mock, _conf));
   }

   protected void tearDown() {
      _log.info("tearDown");
      if (_pool != null)
         _pool.closeAll();
   }

   public void testConnectionSetup() {
      assertEquals(_conf.getIntegerValue("ConnectionPoolSize"),
                   _pool.getTotalConnections());
      _log.info("testConnectinSetup end");
   }

   public void testConnectionCheck() throws IOException, InterruptedException {
      IAthenaConnection conn = _pool.getConnection();
      assertNotNull(conn);
      if (conn != null)
         _pool.putConnection(conn);

      _log.info("testConnectionCheck end");
   }
}
