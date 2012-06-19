package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.OpCode;
import org.eclipse.jetty.websocket.api.WebSocketException;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.5.3">Pong Frame (0x0A)</a>.
 */
public class PongFrame extends ControlFrame
{
    private ByteBuffer payload;

    /**
     * Default contructor
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
     * @param ping the ping frame to base pong from
     */
    public PongFrame(PingFrame ping)
    {
        this();
        // TODO: set appropriate pong from ping frame payload + masking
    }

    @Override
    public OpCode getOpCode()
    {
        return OpCode.PONG;
    }

    public ByteBuffer getPayload()
    {
        return payload;
    }

    public void setPayload(ByteBuffer payload)
    {
        if (payload.array().length >= 126)
        {
            this.payload = payload;
            setPayloadLength(payload.array().length);
        }
        else
        {
            throw new WebSocketException("too long, catch this better");
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s pong",super.toString());
    }
}
