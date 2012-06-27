package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.PingFrame;

public class PingFrameGenerator extends FrameGenerator<PingFrame>
{
    public PingFrameGenerator(WebSocketPolicy policy)
    {
        super(policy);
    }

    @Override
    public void fillPayload(ByteBuffer buffer, PingFrame ping)
    {
        if ( ping.hasPayload() )
        {
            BufferUtil.put(ping.getPayload(),buffer);
        }
    }
}
