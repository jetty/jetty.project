package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.OpCode;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.5.2">Ping Frame (0x09)</a>.
 */
public class PingFrame extends ControlFrame
{
    /**
     * Default constructor
     */
    public PingFrame()
    {
        super(OpCode.PING);
    }

    /**
     * Construct Ping Frame from known byte[]
     * 
     * @param payload
     */
    public PingFrame(byte[] payload)
    {
        this();
        setPayload(payload);
    }

    @Override
    public String toString()
    {
        StringBuilder b = new StringBuilder();
        b.append("PingFrame[");
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
