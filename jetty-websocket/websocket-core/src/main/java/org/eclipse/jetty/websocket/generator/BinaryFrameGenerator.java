package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.BinaryFrame;

public class BinaryFrameGenerator extends FrameGenerator<BinaryFrame>
{
    public BinaryFrameGenerator(WebSocketPolicy policy)
    {
        super(policy);
    }

    @Override
    public void fillPayload(ByteBuffer buffer, BinaryFrame binary)
    {
        BufferUtil.put(binary.getPayload(),buffer);
    }
}
