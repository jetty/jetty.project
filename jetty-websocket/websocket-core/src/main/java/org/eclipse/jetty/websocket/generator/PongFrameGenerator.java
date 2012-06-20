package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.frames.PongFrame;

public class PongFrameGenerator extends FrameGenerator<PongFrame>
{
    public PongFrameGenerator(ByteBufferPool bufferPool, WebSocketPolicy settings)
    {
        super(bufferPool, settings);
    }

    @Override
    public ByteBuffer payload(PongFrame pong)
    {
        return pong.getPayload();
    }
}
