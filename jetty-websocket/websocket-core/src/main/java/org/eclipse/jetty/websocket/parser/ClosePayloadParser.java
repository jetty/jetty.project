package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
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
                payload.flip();
                frame.setStatusCode(payload.getShort());
                if (payload.remaining() > 0)
                {
                    String reason = BufferUtil.toString(payload,StringUtil.__UTF8_CHARSET);
                    frame.setReason(reason);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void reset()
    {
        super.reset();

    }
}
