package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.DataFrame;

/**
 * Parsing for the {@link BinaryFrame}.
 */
public class BinaryPayloadParser extends FrameParser<DataFrame>
{
    private DataFrame frame;
    private ByteBuffer payload;
    private int payloadLength;

    public BinaryPayloadParser(WebSocketPolicy policy)
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
                getPolicy().assertValidBinaryMessageSize(payloadLength);
                payload = ByteBuffer.allocate(payloadLength);
            }

            copyBuffer(buffer,payload,payload.remaining());

            if (payload.position() >= payloadLength)
            {
                frame.setPayload(payload);
                this.payload = null;
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
