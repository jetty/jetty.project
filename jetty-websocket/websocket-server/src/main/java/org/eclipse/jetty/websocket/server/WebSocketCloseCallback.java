package org.eclipse.jetty.websocket.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;

public class WebSocketCloseCallback implements Callback<Void>
{
    private AsyncWebSocketConnection conn;
    private ByteBuffer buf;

    public WebSocketCloseCallback(AsyncWebSocketConnection conn, ByteBuffer buf)
    {
        this.conn = conn;
        this.buf = buf;
    }

    @Override
    public void completed(Void context)
    {
        // release buffer
        this.conn.getBufferPool().release(buf);
        this.conn.getEndPoint().close();
    }

    @Override
    public void failed(Void context, Throwable cause)
    {
        this.conn.getBufferPool().release(buf);
        this.conn.getEndPoint().close();
    }
}
