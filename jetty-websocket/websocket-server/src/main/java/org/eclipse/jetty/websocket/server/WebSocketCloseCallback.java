package org.eclipse.jetty.websocket.server;

import org.eclipse.jetty.util.Callback;

public class WebSocketCloseCallback implements Callback<Void>
{
    private AsyncWebSocketConnection conn;

    public WebSocketCloseCallback(AsyncWebSocketConnection conn)
    {
        this.conn = conn;
    }

    @Override
    public void completed(Void context)
    {
        this.conn.getEndPoint().close();
    }

    @Override
    public void failed(Void context, Throwable cause)
    {
        this.conn.getEndPoint().close();
    }
}
