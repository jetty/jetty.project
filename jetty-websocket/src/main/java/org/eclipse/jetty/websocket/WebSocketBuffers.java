package org.eclipse.jetty.websocket;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ThreadLocalBuffers;
import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;


/* ------------------------------------------------------------ */
/** The WebSocket Buffer Pool.
 * 
 * The normal buffers are byte array buffers so that user processes
 * can access directly.   However the generator uses direct buffers
 * for the final output stage as they are filled in bulk and are more
 * effecient to flush.
 */
public class WebSocketBuffers
{
    final private int _bufferSize;
    final private ThreadLocalBuffers _buffers;
    
    public WebSocketBuffers(final int bufferSize)
    {
        _bufferSize=bufferSize;
        _buffers = new ThreadLocalBuffers()
        {
            @Override
            protected Buffer newHeader(int size)
            {
                return new DirectNIOBuffer(bufferSize);
            }
            
            @Override
            protected Buffer newBuffer(int size)
            {
                return new IndirectNIOBuffer(bufferSize);
            }
            
            @Override
            protected boolean isHeader(Buffer buffer)
            {
                return buffer instanceof DirectNIOBuffer;
            }
        };    
    }
    
    public Buffer getBuffer()
    {
        return _buffers.getBuffer();
    }
    
    public Buffer getDirectBuffer()
    {
        return _buffers.getHeader();
    }
    
    public void returnBuffer(Buffer buffer)
    {
        _buffers.returnBuffer(buffer);
    }

    public int getBufferSize()
    {
        return _bufferSize;
    }
}
