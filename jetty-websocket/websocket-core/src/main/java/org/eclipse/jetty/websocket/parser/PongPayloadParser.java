package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.PongFrame;

public class PongPayloadParser extends FrameParser<PongFrame>
{
    private PongFrame frame;
    private ByteBuffer payload;
    private int payloadLength;

    public PongPayloadParser(WebSocketPolicy policy)
    {
        super(policy);
        frame = new PongFrame();
    }

    @Override
    public PongFrame getFrame()
    {
        return frame;
    }

    @Override
    public PongFrame newFrame()
    {
        frame = new PongFrame();
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
                // TODO: buffer size limits
                payload = ByteBuffer.allocate(payloadLength);
            }

            copyBuffer(buffer,payload,payload.remaining());

            if (payload.position() >= payloadLength)
            {
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
        payload = null;
    }
}
