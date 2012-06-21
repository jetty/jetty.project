package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.OpCode;

public abstract class DataFrame extends BaseFrame
{
    // internal tracking
    private int continuationIndex = 0;
    private boolean continuation = false;
    private ByteBuffer payload;

    public DataFrame()
    {
        super();
    }
    
    public DataFrame(OpCode opcode)
    {
        super(opcode);
    }

    /**
     * Get the data
     * 
     * @return the raw bytebuffer data (can be null)
     */
    public ByteBuffer getPayload()
    {
        return payload;
    }
    
    /**
     * Set the data and payload length.
     * 
     * @param buf
     *            the bytebuffer to set
     */
    protected void setPayload(byte buf[])
    {
        int len = buf.length;
        this.payload = ByteBuffer.allocate(len);
        this.setPayloadLength(len);
    }
    

    /**
     * Set the data and payload length.
     * 
     * @param buf
     *            the byte array to set
     */
    protected void setPayload(ByteBuffer buffer)
    {
        this.payload = buffer;
        this.setPayloadLength(buffer.capacity());
    }

    
    /**
     * The number of fragments this frame consists of.
     * <p>
     * For every {@link OpCode#CONTINUATION} opcode encountered, this increments by one.
     * <p>
     * Note: Not part of the Base Framing Protocol / header information.
     * 
     * @return the number of continuation fragments encountered.
     */
    public int getContinuationIndex()
    {
        return continuationIndex;
    }
    
    public void setContinuationIndex(int continuationIndex)
    {
        this.continuationIndex = continuationIndex;
    }

    @Override
    public void reset()
    {
        super.reset();
        continuationIndex = 0;
    }

    public boolean isContinuation()
    {
        return continuation;
    }

    public void setContinuation(boolean continuation)
    {
        this.continuation = continuation;
    }
    
}
