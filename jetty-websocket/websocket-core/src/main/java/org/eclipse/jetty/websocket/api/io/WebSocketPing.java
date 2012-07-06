package org.eclipse.jetty.websocket.api.io;

import org.eclipse.jetty.websocket.api.WebSocketConnection;

public class WebSocketPing
{
    private WebSocketConnection conn;

    public WebSocketPing(WebSocketConnection conn)
    {
        this.conn = conn;
    }

    public void sendPing(byte buf[], int offset, int len)
    {
        // TODO: implement
        // TODO: should this block and wait for a pong? (how?)
    }
}
