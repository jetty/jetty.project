package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.frames.ControlFrame;

public class PongFrameGenerator extends ControlFrameGenerator
{
    public PongFrameGenerator(ByteBufferPool bufferPool)
    {
        super(bufferPool);
    }
    
    @Override
    public ByteBuffer generate(ControlFrame frame)
    {
        // TODO Auto-generated method stub
        return null;
    }

}
