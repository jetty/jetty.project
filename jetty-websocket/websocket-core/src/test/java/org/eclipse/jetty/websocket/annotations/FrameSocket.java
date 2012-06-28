package org.eclipse.jetty.websocket.annotations;

import org.eclipse.jetty.websocket.frames.BaseFrame;

@WebSocket
public class FrameSocket
{
    @OnWebSocketFrame
    public void frameMe(BaseFrame frame)
    {
        /* ignore */
    }
}
