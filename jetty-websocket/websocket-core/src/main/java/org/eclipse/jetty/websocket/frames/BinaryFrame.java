package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.protocol.OpCode;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.6">Binary Data Frame (0x02)</a>.
 */
public class BinaryFrame extends DataFrame
{
    /**
     * Default unspecified data
     */
    public BinaryFrame()
    {
        super(OpCode.BINARY);
    }

    /**
     * Construct with byte array payload data
     */
    public BinaryFrame( byte[] payload )
    {
        this();
        setPayload(payload);
    }

    /**
     * Construct with partial byte array payload data support
     */
    public BinaryFrame(byte[] data, int offset, int length)
    {
        this();
        setPayload(data,offset,length);
    }

    /**
     * Construct with ByteBuffer payload data
     */
    public BinaryFrame(ByteBuffer payload)
    {
        this();
        setPayload(payload);
    }

    @Override
    public String toString()
    {
        StringBuilder b = new StringBuilder();
        b.append("BinaryFrame[");
        b.append("len=").append(getPayloadLength());
        b.append(",data=").append(BufferUtil.toDetailString(getPayload()));
        b.append("]");
        return b.toString();
    }
}
