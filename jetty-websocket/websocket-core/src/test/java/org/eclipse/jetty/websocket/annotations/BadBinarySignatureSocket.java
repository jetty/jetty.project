package org.eclipse.jetty.websocket.annotations;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketConnection;

@WebSocket
public class BadBinarySignatureSocket
{
    /**
     * Declaring a non-void return type
     */
    @OnWebSocketBinary
    public boolean onBinary(WebSocketConnection conn, ByteBuffer payload)
    {
        return false;
    }
}
