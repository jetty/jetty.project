package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.ControlFrame;

public abstract class ControlFrameGenerator 
{
    private final ByteBufferPool bufferPool;

    protected ControlFrameGenerator(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
    }

    protected ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }
    
    public ByteBuffer generate(ControlFrame frame)
    {
        // how to calculate the size since control frames may hold 
        // application data
        // grabing 125 now since that is _max_ possible
        ByteBuffer buffer = getByteBufferPool().acquire(125,true);

        // all control frames are FIN as they can not be fragmented
        buffer.putInt(BaseFrame.FLAG_FIN);
        
        // revisit this on extensions since they can negotiate this
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        
        return buffer;
    }
}
