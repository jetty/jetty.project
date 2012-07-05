package org.eclipse.jetty.websocket.annotations;

import org.eclipse.jetty.websocket.api.Frame;

@WebSocket
public class BadDuplicateFrameSocket
{
    /**
     * The get a frame
     */
    @OnWebSocketFrame
    public void frameMe(Frame frame)
    {
        /* ignore */
    }

    /**
     * This is a duplicate frame type (should throw an exception attempting to use)
     */
    @OnWebSocketFrame
    public void watchMe(Frame frame)
    {
        /* ignore */
    }
}
