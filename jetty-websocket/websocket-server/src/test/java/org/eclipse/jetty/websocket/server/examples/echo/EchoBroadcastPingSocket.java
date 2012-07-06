package org.eclipse.jetty.websocket.server.examples.echo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.io.WebSocketPing;

@WebSocket
public class EchoBroadcastPingSocket extends EchoBroadcastSocket
{
    private static class KeepAlive extends Thread
    {
        private CountDownLatch latch;
        private WebSocketPing pinger;

        public KeepAlive(WebSocketConnection conn)
        {
            this.pinger = new WebSocketPing(conn);
        }

        @Override
        public void run()
        {
            try
            {
                while (!latch.await(10,TimeUnit.SECONDS))
                {
                    System.err.println("Ping " + pinger);
                    byte data[] = new byte[]
                    { (byte)1, (byte)2, (byte)3 };
                    pinger.sendPing(data);
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

    private KeepAlive keepAlive; // A dedicated thread is not a good way to do this

    public EchoBroadcastPingSocket()
    {
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
        if (keepAlive == null)
        {
            keepAlive = new KeepAlive(conn);
        }
        keepAlive.start();
        super.onOpen(conn);
    }
}
