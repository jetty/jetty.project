package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.WebSocketSettings;
import org.eclipse.jetty.websocket.frames.CloseFrame;

public class CloseFrameGenerator extends FrameGenerator<CloseFrame>
{
    public CloseFrameGenerator(ByteBufferPool bufferPool, WebSocketSettings settings)
    {
        super(bufferPool, settings);
    }

    @Override
    public void generatePayload(ByteBuffer buffer, CloseFrame close)
    {
        buffer.put(close.getReason().getBytes());
    }
}
