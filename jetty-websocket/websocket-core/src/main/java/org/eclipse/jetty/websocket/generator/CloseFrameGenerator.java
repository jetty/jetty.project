package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.eclipse.jetty.websocket.util.CloseUtil;

public class CloseFrameGenerator extends FrameGenerator
{
    public CloseFrameGenerator(WebSocketPolicy policy)
    {
        super(policy);
    }

    @Override
    public void fillPayload(ByteBuffer buffer, WebSocketFrame close)
    {
        CloseUtil.assertValidPayload(close.getPayload());
        super.fillPayload(buffer,close);
    }
}
