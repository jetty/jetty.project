package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.OpCode;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.6">Binary Data Frame (0x02)</a>.
 */
public class BinaryFrame extends DataFrame
{
    private ByteBuffer data; // TODO: make this a standard byte buffer?

    /**
     * Default unspecified data
     */
    public BinaryFrame()
    {
        super(OpCode.BINARY);
    }

    /**
     * Get the data
     * 
     * @return the raw bytebuffer data (can be null)
     */
    public ByteBuffer getData()
    {
        return data;
    }

    @Override
    public OpCode getOpCode()
    {
        return OpCode.BINARY;
    }

    /**
     * Set the data and payload length.
     * 
     * @param buf
     *            the bytebuffer to set
     */
    public void setData(byte buf[])
    {
        int len = buf.length;
        this.data = ByteBuffer.allocate(len);
        this.setPayloadLength(len);
    }

    /**
     * Set the data and payload length.
     * 
     * @param buf
     *            the byte array to set
     */
    public void setData(ByteBuffer buffer)
    {
        this.data = buffer;
        this.setPayloadLength(buffer.capacity());
    }

    @Override
    public String toString()
    {
        StringBuilder b = new StringBuilder();
        b.append("BinaryFrame[");
        b.append("len=").append(getPayloadLength());
        b.append(",data=").append(BufferUtil.toDetailString(getData()));
        b.append("]");
        return b.toString();
    }
}
