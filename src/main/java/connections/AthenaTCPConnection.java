/**
 * This singleton class is used to maintain resources related to a single
 * TCP connection with Athena.
 *
 * Also contains functions for communicating with Athena over a TCP connection.
 *
 * These resources are shared by all servlets.
 * This means that at present, only one servlet can talk to Athena at a
 * time.
 */
package connections;

import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.log4j.Logger;

import configurations.*;

public final class AthenaTCPConnection implements IAthenaConnection {
   private Socket _socket;
   private AtomicBoolean _disposed;
   private final int _receiveTimeout; // ms
   private final int _receiveLengthSize; // bytes
   private IConfiguration _conf;
   private static Logger _logger =
         Logger.getLogger(AthenaTCPConnection.class);

   /**
   * Sets up a TCP connection with Athena
   *
   * @throws IOException
   */
   public AthenaTCPConnection(IConfiguration conf) throws IOException {
      _conf = conf;
      _receiveLengthSize =
            _conf.getIntegerValue("ReceiveHeaderSizeBytes");
      _receiveTimeout = _conf.getIntegerValue("ReceiveTimeoutMs");
      _disposed = new AtomicBoolean(false);

      // Create the TCP connection and input and output streams
      try {
         String host = _conf.getStringValue("AthenaHostName");
         int port = _conf.getIntegerValue("AthenaPort");
         _socket = 
               new Socket(host, port);
         _socket.setTcpNoDelay(true);
      } catch (UnknownHostException e) {
         _logger.error("Error creating TCP connection with Athena");
         throw new UnknownHostException();
      } catch (IOException e) {
         _logger.error("Error creating input/output stream with Athena");
         throw new IOException();
      }

      _logger.debug("Socket connection with Athena created");
   }
   
   public void close() {
      if (_disposed.get())
         return;

      if (_socket != null && !_socket.isClosed()) {
         try {
            _socket.close();
         } catch (IOException e) {
            _logger.error("Error in closing TCP socket");
         } finally {
            _disposed.set(true);
         }
      }
   }

   @Override
   protected void finalize() throws Throwable {
      _logger.info("connection disposed");
      try {
         if (!_disposed.get())
            close();
      } finally {
         super.finalize();
      }
   }

   @Override
   public byte[] receive() {
      try {
         java.io.InputStream is = _socket.getInputStream();
         long start = System.currentTimeMillis();
         ByteBuffer length = null;
         byte[] res = null;
         int read = 0;
         boolean done = false;
         while (System.currentTimeMillis() - start < _receiveTimeout) {
            if (length == null && is.available() >= _receiveLengthSize) {
               length = ByteBuffer.wrap(new byte[_receiveLengthSize])
                     .order(ByteOrder.LITTLE_ENDIAN);
               is.read(length.array(), 0, _receiveLengthSize);
            }

            int msgSize = 0;
            if (length != null && res == null) {
               msgSize = length.getShort();
               res = new byte[msgSize];
            }

            if (res != null) {
               int av = is.available();
               if (av > 0)
                  read += is.read(res, read, av);
               if (read == msgSize) {
                  done = true;
                  break;
               }
            }
         }

         if (!done) {
            close();
            return null;
         }

         return res;
      } catch (IOException e) {
         _logger.error("readMessage", e);
         return null;
      }
   }

   @Override
   public boolean send(byte[] msg) {
      try {
         _socket.getOutputStream().write(msg);
         return true;
      } catch (Exception e) {
         _logger.error("sendMessage", e);
         return false;
      }
   }
}
