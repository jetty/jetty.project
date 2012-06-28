package org.eclipse.jetty.websocket.annotations;

import org.eclipse.jetty.websocket.api.WebSocketConnection;

@WebSocket
public class BadTextSignatureSocket
{
    /**
     * Declaring a static method
     */
    @OnWebSocketText
    public static void onText(WebSocketConnection conn, String text)
    {
        /* do nothing */
    }
}
