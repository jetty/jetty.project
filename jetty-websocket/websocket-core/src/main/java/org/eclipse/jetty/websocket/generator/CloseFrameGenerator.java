package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.CloseFrame;

public class CloseFrameGenerator extends FrameGenerator<CloseFrame>
{
    public CloseFrameGenerator(WebSocketPolicy policy)
    {
        super(policy);
    }

    @Override
    public void fillPayload(ByteBuffer buffer, CloseFrame close)
    {
        buffer.putShort(close.getStatusCode());
        if (close.hasReason())
        {
            byte utf[] = close.getReason().getBytes(StringUtil.__UTF8_CHARSET);
            buffer.put(utf,0,utf.length);
        }
    }
}
