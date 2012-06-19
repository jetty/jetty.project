package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.frames.PingFrame;

public class PingFrameGenerator extends FrameGenerator<PingFrame>
{
    public PingFrameGenerator(ByteBufferPool bufferPool)
    {
        super(bufferPool);
    }

    @Override
    public void generatePayload(ByteBuffer buffer, PingFrame ping)
    {
        buffer.put(ping.getPayload().array());
    }
}
