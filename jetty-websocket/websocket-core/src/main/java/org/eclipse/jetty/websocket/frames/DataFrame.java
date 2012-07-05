package org.eclipse.jetty.websocket.frames;

import org.eclipse.jetty.websocket.protocol.OpCode;

public class DataFrame extends BaseFrame
{
    // internal tracking
    private int continuationIndex = 0;
    private boolean continuation = false;

    public DataFrame()
    {
        super();
    }

    public DataFrame(OpCode opcode)
    {
        super(opcode);
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

    @Override
    public boolean isContinuation()
    {
        return continuation;
    }

    @Override
    public void reset()
    {
        super.reset();
        continuationIndex = 0;
    }

    public void setContinuation(boolean continuation)
    {
        this.continuation = continuation;
    }

    public void setContinuationIndex(int continuationIndex)
    {
        this.continuationIndex = continuationIndex;
    }
}
