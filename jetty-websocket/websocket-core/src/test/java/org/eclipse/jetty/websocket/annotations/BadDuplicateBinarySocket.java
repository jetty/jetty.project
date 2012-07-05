package org.eclipse.jetty.websocket.annotations;

import java.io.InputStream;

/**
 * Invalid Socket: Annotate 2 methods with interest in Binary Messages.
 */
@WebSocket
public class BadDuplicateBinarySocket
{
    /**
     * First method
     */
    @OnWebSocketMessage
    public void binMe(byte[] payload, int offset, int len)
    {
        /* ignore */
    }

    /**
     * Second method
     */
    @OnWebSocketMessage
    public void streamMe(InputStream stream)
    {
        /* ignore */
    }
}
