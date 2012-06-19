package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.frames.PingFrame;

/**
 * Parsing for the {@link PingFrame}.
 */
public class PingPayloadParser extends FrameParser<PingFrame>
{
    private PingFrame frame;
    private ByteBuffer payload;
    private int payloadLength;

    public PingPayloadParser()
    {
        super();
        frame = new PingFrame();
    }

    @Override
    public PingFrame getFrame()
    {
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

            int size = Math.min(payloadLength,buffer.remaining());
            int limit = buffer.limit();
            buffer.limit(buffer.position() + size);
            ByteBuffer bytes = buffer.slice(); // TODO: make sure reference to subsection is acceptable
            buffer.limit(limit);
            payload.put(bytes);
            if (payload.position() >= payloadLength)
            {
                frame.setPayload(bytes);
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
