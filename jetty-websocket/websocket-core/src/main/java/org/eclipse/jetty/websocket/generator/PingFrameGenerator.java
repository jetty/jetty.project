package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.PingFrame;

public class PingFrameGenerator extends FrameGenerator<PingFrame>
{
    public PingFrameGenerator(ByteBufferPool bufferPool, WebSocketPolicy settings)
    {
        super(bufferPool, settings);
    }

    @Override
    public ByteBuffer payload(PingFrame ping)
    {
        return ping.getPayload();
    }
}
