package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.protocol.OpCode;

public class DataFrame extends BaseFrame
{
    public class BinaryFrame extends DataFrame
    {
        public BinaryFrame(byte[] payload)
        {
            super(OpCode.BINARY);
            super.setPayload(payload);
        }

        public BinaryFrame(ByteBuffer payload)
        {
            super(OpCode.BINARY);
            super.setPayload(payload);
        }
    }

    public class TextFrame extends DataFrame
    {
        public TextFrame(String message)
        {
            super(OpCode.TEXT);
            super.setPayload(message.getBytes());
        }

        public String getPayloadUTF8()
        {
            return new String(getPayloadData(),StringUtil.__UTF8_CHARSET);
        }
    }

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
