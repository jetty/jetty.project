package org.eclipse.jetty.websocket.annotations;

import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;

@WebSocket
public class BadDuplicateFrameSocket
{
    /**
     * The most basic frame type
     */
    @OnWebSocketFrame
    public void frameMe(BaseFrame frame)
    {
        /* ignore */
    }

    /**
     * Should allow for a more specific frame type as well.
     */
    @OnWebSocketFrame
    public void messageMe(TextFrame frame)
    {
        /* ignore */
    }

    /**
     * This is a duplicate frame type (should throw an exception attempting to use)
     */
    @OnWebSocketFrame
    public void textMe(TextFrame frame)
    {
        /* ignore */
    }
}
