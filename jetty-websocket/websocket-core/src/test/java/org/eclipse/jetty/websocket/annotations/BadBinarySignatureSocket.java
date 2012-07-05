package org.eclipse.jetty.websocket.annotations;

import org.eclipse.jetty.websocket.api.WebSocketConnection;

/**
 * Invalid Socket: Annotate a message interest on a method with a return type.
 */
@WebSocket
public class BadBinarySignatureSocket
{
    /**
     * Declaring a non-void return type
     */
    @OnWebSocketMessage
    public boolean onBinary(WebSocketConnection conn, byte buf[], int offset, int len)
    {
        return false;
    }
}
