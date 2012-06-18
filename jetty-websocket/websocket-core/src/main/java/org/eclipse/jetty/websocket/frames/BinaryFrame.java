package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.6">Binary Data Frame (0x02)</a>.
 */
public class BinaryFrame extends BaseFrame
{
    private ByteBuffer data; // TODO: make this a standard byte buffer?

    /**
     * Default unspecified data
     */
    public BinaryFrame()
    {
        super();
        super.setOpcode(OP_BINARY);
    }

    /**
     * Copy Constructor
     * 
     * @param base
     *            the base frame to work off of.
     */
    public BinaryFrame(BaseFrame base)
    {
        super(base);
        // TODO: limit this somehow?
        // TODO: create a streaming binary frame?
        data = ByteBuffer.allocate((int)base.getPayloadLength());
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
}
