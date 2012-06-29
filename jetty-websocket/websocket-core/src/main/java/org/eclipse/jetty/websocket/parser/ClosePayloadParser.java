package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import javax.xml.ws.ProtocolException;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.CloseFrame;

/**
 * Parsing for the {@link CloseFrame}.
 */
public class ClosePayloadParser extends FrameParser<CloseFrame>
{
    private CloseFrame frame;
    private ByteBuffer payload;
    private int payloadLength;

    public ClosePayloadParser(WebSocketPolicy policy)
    {
        super(policy);
        frame = new CloseFrame();
    }

    @Override
    public CloseFrame getFrame()
    {
        return frame;
    }

    @Override
    public CloseFrame newFrame()
    {
        frame = new CloseFrame();
        return frame;
    }

    @Override
    public boolean parsePayload(ByteBuffer buffer)
    {
        payloadLength = getFrame().getPayloadLength();
        if (payloadLength == 0)
        {
            // no status code. no reason.
            return true;
        }

        /*
         * invalid payload length.
         */
        if ((payloadLength == 1) || (payloadLength > 125))
        {
            throw new ProtocolException("Close: invalid payload length: " + payloadLength);
        }

        if (payload == null)
        {
            getPolicy().assertValidTextMessageSize(payloadLength);
            payload = ByteBuffer.allocate(payloadLength);
        }

        while (buffer.hasRemaining())
        {
            copyBuffer(buffer,payload,payload.remaining());

            if (payload.position() >= payloadLength)
            {
                frame.setPayload(payload);
                frame.assertValidPerPolicy(getPolicy().getBehavior());
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
