package org.eclipse.jetty.websocket.helper;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.WebSocket;

public class MessageSender implements WebSocket
{
    private Connection conn;
    private CountDownLatch connectLatch = new CountDownLatch(1);

    public void onOpen(Connection connection)
    {
        this.conn = connection;
        connectLatch.countDown();
    }

    public void onClose(int closeCode, String message)
    {
        this.conn = null;
    }

    public boolean isConnected()
    {
        if (this.conn == null)
        {
            return false;
        }
        return this.conn.isOpen();
    }

    public void sendMessage(String format, Object... args) throws IOException
    {
        this.conn.sendMessage(String.format(format,args));
    }

    public void awaitConnect() throws InterruptedException
    {
        connectLatch.await(1,TimeUnit.SECONDS);
    }

    public void close()
    {
        if (this.conn == null)
        {
            return;
        }
        this.conn.close();
    }
}