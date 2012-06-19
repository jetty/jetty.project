package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketSettings;
import org.eclipse.jetty.websocket.frames.PingFrame;

/**
 * Parsing for the {@link PingFrame}.
 */
public class PingPayloadParser extends FrameParser<PingFrame>
{
    private PingFrame frame;
    private ByteBuffer payload;
    private int payloadLength;

    public PingPayloadParser(WebSocketSettings settings)
    {
        super(settings);
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
