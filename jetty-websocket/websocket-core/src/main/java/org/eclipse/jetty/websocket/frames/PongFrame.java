package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.OpCode;

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
    public OpCode getOpCode()
    {
        return OpCode.PONG;
    }

    @Override
    public String toString()
    {
        return String.format("%s pong, payload=%s",super.toString(), hasPayload());
    }
}
