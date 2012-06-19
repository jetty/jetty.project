package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.WebSocketSettings;
import org.eclipse.jetty.websocket.frames.PongFrame;

public class PongFrameGenerator extends FrameGenerator<PongFrame>
{
    public PongFrameGenerator(ByteBufferPool bufferPool, WebSocketSettings settings)
    {
        super(bufferPool, settings);
    }

    @Override
    public void generatePayload(ByteBuffer buffer, PongFrame frame)
    {
        // TODO Auto-generated method stub
    }
}
