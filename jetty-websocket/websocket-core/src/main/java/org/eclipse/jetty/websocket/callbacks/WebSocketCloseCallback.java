package org.eclipse.jetty.websocket.callbacks;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.io.WebSocketAsyncConnection;

public class WebSocketCloseCallback implements Callback<Void>
{
    private WebSocketAsyncConnection conn;
    private ByteBuffer buf;

    public WebSocketCloseCallback(WebSocketAsyncConnection conn)
    {
        this.conn = conn;
        this.buf = null;
    }

    public WebSocketCloseCallback(WebSocketAsyncConnection conn, ByteBuffer buf)
    {
        this.conn = conn;
        this.buf = buf;
    }

    @Override
    public void completed(Void context)
    {
        if (buf != null)
        {
            // release buffer
            this.conn.getBufferPool().release(buf);
        }
        this.conn.getEndPoint().close();
    }

    @Override
    public void failed(Void context, Throwable cause)
    {
        if (buf != null)
        {
            // release buffer
            this.conn.getBufferPool().release(buf);
        }
        this.conn.getEndPoint().close();
    }
}
