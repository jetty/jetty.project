package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.frames.PingFrame;

/**
 * Parsing for the {@link PingFrame}.
 */
public class PingPayloadParser extends PayloadParser
{
    private Parser baseParser;
    private ByteBuffer payload;
    private int payloadLength;

    public PingPayloadParser(Parser parser)
    {
        this.baseParser = parser;
    }

    private void onPingFrame()
    {
        PingFrame ping = new PingFrame();
        ping.copy(baseParser.getBaseFrame());
        ping.setPayload(payload);
        baseParser.notifyControlFrame(ping);
    }

    @Override
    public boolean parse(ByteBuffer buffer)
    {
        payloadLength = baseParser.getBaseFrame().getPayloadLength();
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
            ByteBuffer bytes = buffer.slice();
            buffer.limit(limit);
            payload.put(bytes);
            if (payload.position() >= payloadLength)
            {
                onPingFrame();
                return true;
            }
        }
        return false;
    }

    @Override
    public void reset()
    {
        payload = null;
    }
}
