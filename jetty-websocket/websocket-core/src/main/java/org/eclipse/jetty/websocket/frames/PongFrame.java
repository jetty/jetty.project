package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.protocol.OpCode;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.5.3">Pong Frame (0x0A)</a>.
 */
public class PongFrame extends ControlFrame
{
    /**
     * Default constructor
     */
    public PongFrame()
    {
        super(OpCode.PONG);
    }

    /**
     * Construct Pong frame from known byteBuffer
     * 
     * @param payload
     */
    public PongFrame(ByteBuffer payload)
    {
        this();
        setPayload(payload);
    }

    /**
     * Construct appropriate PongFrame from PingFrame
     * 
     * @param ping
     *            the ping frame to base pong from
     */
    public PongFrame(PingFrame ping)
    {
        this();
        if (ping.isMasked())
        {
            int mlen = ping.getMask().length;
            byte maskCopy[] = new byte[mlen];
            System.arraycopy(ping.getMask(),0,maskCopy,0,mlen);
            this.setMask(maskCopy);
        }
        setPayload(ping.getPayload());
    }

    @Override
    public String toString()
    {
        StringBuilder b = new StringBuilder();
        b.append("PongFrame[");
        b.append("len=").append(getPayloadLength());
        if (hasPayload())
        {
            b.append(",payload=");
            b.append(BufferUtil.toSummaryString(getPayload()));
        }
        else
        {
            b.append(",no-payload");
        }
        b.append("]");
        return b.toString();
    }
}
