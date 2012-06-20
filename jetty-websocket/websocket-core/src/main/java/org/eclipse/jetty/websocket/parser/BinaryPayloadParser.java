package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.BinaryFrame;

/**
 * Parsing for the {@link BinaryFrame}.
 */
public class BinaryPayloadParser extends FrameParser<BinaryFrame>
{
    private BinaryFrame frame;
    private ByteBuffer payload;
    private int payloadLength;

    public BinaryPayloadParser(WebSocketPolicy settings)
    {
        super(settings);
        frame = new BinaryFrame();
    }

    @Override
    public BinaryFrame getFrame()
    {
        return frame;
    }

    @Override
    public BinaryFrame newFrame()
    {
        frame = new BinaryFrame();
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
                frame.setData(payload);
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
