package org.eclipse.jetty.websocket.api;

import org.eclipse.jetty.websocket.api.io.WebSocketBlockingConnection;

/**
 * Default implementation of the {@link WebSocketListener}.
 * <p>
 * Convenient abstract class to base standard WebSocket implementations off of.
 */
public class WebSocketAdapter implements WebSocketListener
{
    private WebSocketConnection connection;
    private WebSocketBlockingConnection blocking;

    public WebSocketBlockingConnection getBlockingConnection()
    {
        return blocking;
    }

    public WebSocketConnection getConnection()
    {
        return connection;
    }

    public boolean isConnected()
    {
        return (connection != null) && (connection.isOpen());
    }

    public boolean isNotConnected()
    {
        return (connection == null) || (!connection.isOpen());
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        /* do nothing */
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        this.connection = null;
    }

    @Override
    public void onWebSocketConnect(WebSocketConnection connection)
    {
        this.connection = connection;
        this.blocking = new WebSocketBlockingConnection(this.connection);
    }

    @Override
    public void onWebSocketException(WebSocketException error)
    {
        /* do nothing */
    }

    @Override
    public void onWebSocketText(String message)
    {
        /* do nothing */
    }
}
