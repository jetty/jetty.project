package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.BinaryFrame;

public class BinaryFrameGenerator extends FrameGenerator<BinaryFrame>
{    
    public BinaryFrameGenerator(ByteBufferPool bufferPool, WebSocketPolicy policy)
    {
        super(bufferPool, policy);
    }

    @Override
    public ByteBuffer payload(BinaryFrame binary)
    {
        return binary.getPayload();
    }
}
