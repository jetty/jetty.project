package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.TextFrame;

public class TextFrameGenerator extends FrameGenerator<TextFrame>
{
    public TextFrameGenerator(WebSocketPolicy policy)
    {
        super(policy);
    }

    @Override
    public void fillPayload(ByteBuffer buffer, TextFrame text)
    {
        BufferUtil.put(text.getPayload(),buffer);
    }
}
