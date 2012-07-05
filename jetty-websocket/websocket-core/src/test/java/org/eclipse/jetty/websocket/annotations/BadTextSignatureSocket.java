package org.eclipse.jetty.websocket.annotations;

import org.eclipse.jetty.websocket.api.WebSocketConnection;

/**
 * Invalid Socket: Annotate a message interest on a static method
 */
@WebSocket
public class BadTextSignatureSocket
{
    /**
     * Declaring a static method
     */
    @OnWebSocketMessage
    public static void onText(WebSocketConnection conn, String text)
    {
        /* do nothing */
    }
}
