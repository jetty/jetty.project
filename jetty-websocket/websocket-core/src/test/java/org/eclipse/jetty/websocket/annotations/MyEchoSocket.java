package org.eclipse.jetty.websocket.annotations;

import java.io.IOException;

import org.eclipse.jetty.websocket.api.WebSocketConnection;

/**
 * The most common websocket implementation.
 * <p>
 * This version tracks the connection per socket instance and will
 */
@WebSocket
public class MyEchoSocket
{
    private WebSocketConnection conn;

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        this.conn = null;
    }

    @OnWebSocketConnect
    public void onConnect(WebSocketConnection conn)
    {
        this.conn = conn;
    }

    @OnWebSocketText
    public void onText(String message)
    {
        if (conn == null)
        {
            // no connection, do nothing.
            // this is possible due to async behavior
            return;
        }

        try
        {
            conn.write(message);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}
