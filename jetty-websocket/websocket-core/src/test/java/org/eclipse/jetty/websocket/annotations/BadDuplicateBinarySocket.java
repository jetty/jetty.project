package org.eclipse.jetty.websocket.annotations;

import java.nio.ByteBuffer;

@WebSocket
public class BadDuplicateBinarySocket
{
    /**
     * First method
     */
    @OnWebSocketBinary
    public void binMe(byte[] payload, int offset, int len)
    {
        /* ignore */
    }

    /**
     * Second method
     */
    @OnWebSocketBinary
    public void binMe(ByteBuffer payload)
    {
        /* ignore */
    }
}
