package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.ControlFrame;

public class CloseFrameGenerator extends ControlFrameGenerator
{
    public CloseFrameGenerator(ByteBufferPool bufferPool)
    {
        super(bufferPool);
    }
    @Override
    public ByteBuffer generate(ControlFrame frame)
    {
        CloseFrame close = (CloseFrame)frame;
        
        ByteBuffer buffer = super.generate(frame);
        
        buffer.putInt(BaseFrame.OP_CLOSE);
        buffer.put(frame.getMask());
        buffer.putLong(frame.getPayloadLength());
        buffer.put(close.getReason().getBytes());
        
        return buffer;
    }

}
