package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.PingFrame;

public class CloseFrameGenerator extends FrameGenerator<CloseFrame>
{
    public CloseFrameGenerator(ByteBufferPool bufferPool, WebSocketPolicy settings)
    {
        super(bufferPool, settings);
    }

    @Override
    public ByteBuffer payload(CloseFrame close)
    {
        ByteBuffer b = ByteBuffer.allocate(close.getReason().length() + 2);
        
        b.putShort(close.getStatusCode());
        b.put(close.getReason().getBytes()); // TODO force UTF-8 and work out ex handling
        
        return b;
    }
}
