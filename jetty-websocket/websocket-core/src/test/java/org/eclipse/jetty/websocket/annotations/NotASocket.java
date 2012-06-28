package org.eclipse.jetty.websocket.annotations;

import org.eclipse.jetty.websocket.api.WebSocketConnection;

/**
 * (Test Case)
 * <p>
 * Intentionally not specifying the @WebSocket annotation here
 */
public class NotASocket
{
    @OnWebSocketConnect
    public void onConnect(WebSocketConnection conn)
    {
        /* do nothing */
    }
}
