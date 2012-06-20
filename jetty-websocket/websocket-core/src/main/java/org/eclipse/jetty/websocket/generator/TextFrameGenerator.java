package org.eclipse.jetty.websocket.generator;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.TextFrame;

public class TextFrameGenerator extends FrameGenerator<TextFrame>
{    
    public TextFrameGenerator(ByteBufferPool bufferPool, WebSocketPolicy policy)
    {
        super(bufferPool, policy);
    }

    @Override
    public ByteBuffer payload(TextFrame text)
    {
        try
        {
            String data = text.getData().toString();
            ByteBuffer payload = ByteBuffer.allocate(data.length());
            payload.put(data.getBytes("UTF-8"));
            return payload;
        }
        catch (UnsupportedEncodingException e)
        {
            // TODO improve ex handling
            throw new WebSocketException("text frame was not correctly encoded");
        }

    }
}
