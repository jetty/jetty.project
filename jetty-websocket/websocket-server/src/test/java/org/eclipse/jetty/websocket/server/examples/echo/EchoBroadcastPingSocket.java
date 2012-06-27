package org.eclipse.jetty.websocket.server.examples.echo;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.frames.PingFrame;

@WebSocket
public class EchoBroadcastPingSocket extends EchoBroadcastSocket
{
    private class KeepAlive extends Thread
    {
        private CountDownLatch latch;

        private WebSocketConnection getConnection()
        {
            return EchoBroadcastPingSocket.this.conn;
        }

        @Override
        public void run()
        {
            try
            {
                while (!latch.await(10,TimeUnit.SECONDS))
                {
                    System.err.println("Ping " + getConnection());
                    PingFrame ping = new PingFrame();
                    ByteBuffer payload = ByteBuffer.allocate(3);
                    payload.put((byte)1);
                    payload.put((byte)2);
                    payload.put((byte)3);
                    ping.setPayload(payload);
                    getConnection().write(ping);
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        public void shutdown()
        {
            if (latch != null)
            {
                latch.countDown();
            }
        }

        @Override
        public synchronized void start()
        {
            latch = new CountDownLatch(1);
            super.start();
        }
    }

    private final KeepAlive keepAlive; // A dedicated thread is not a good way to do this

    public EchoBroadcastPingSocket()
    {
        keepAlive = new KeepAlive();
    }

    @Override
    public void onClose(int statusCode, String reason)
    {
        keepAlive.shutdown();
        super.onClose(statusCode,reason);
    }

    @Override
    public void onOpen(WebSocketConnection conn)
    {
        keepAlive.start();
        super.onOpen(conn);
    }
}
