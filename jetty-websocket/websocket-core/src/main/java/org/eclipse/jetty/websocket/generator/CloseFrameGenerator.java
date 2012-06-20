package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.CloseFrame;

public class CloseFrameGenerator extends FrameGenerator<CloseFrame>
{
    public CloseFrameGenerator(ByteBufferPool bufferPool, WebSocketPolicy policy)
    {
        super(bufferPool, policy);
    }

    @Override
    public ByteBuffer payload(CloseFrame close)
    {
        ByteBuffer b = ByteBuffer.allocate(close.getReason().length() + 2);

        b.putShort(close.getStatusCode());
        byte utf[] = close.getReason().getBytes(StringUtil.__UTF8_CHARSET);
        b.put(utf,0,utf.length);

        return b;
    }
}
