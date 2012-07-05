package org.eclipse.jetty.websocket.annotations;

import org.eclipse.jetty.websocket.protocol.Frame;

@WebSocket
public class FrameSocket
{
    /**
     * A frame
     */
    @OnWebSocketFrame
    public void frameMe(Frame frame)
    {
        /* ignore */
    }
}
