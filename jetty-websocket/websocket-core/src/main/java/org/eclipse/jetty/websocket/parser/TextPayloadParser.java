package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.DataFrame;

public class TextPayloadParser extends FrameParser<DataFrame>
{
    private DataFrame frame;
    private ByteBuffer payload;
    private int payloadLength;

    public TextPayloadParser(WebSocketPolicy policy)
    {
        super(policy);
        frame = new DataFrame();
    }

    @Override
    public DataFrame getFrame()
    {
        return frame;
    }

    @Override
    public DataFrame newFrame()
    {
        frame = new DataFrame();
        return frame;
    }

    @Override
    public boolean parsePayload(ByteBuffer buffer)
    {
        payloadLength = getFrame().getPayloadLength();
        while (buffer.hasRemaining())
        {
            if (payload == null)
            {
                getPolicy().assertValidTextMessageSize(payloadLength);
                payload = ByteBuffer.allocate(payloadLength);
            }

            copyBuffer(buffer,payload,payload.remaining());

            if (payload.position() >= payloadLength)
            {
                payload.flip();
                frame.setPayload(payload);
                return true;
            }
        }
        return false;
    }

    @Override
    public void reset()
    {
        super.reset();
        payloadLength = 0;
        payload = null;
    }
}
