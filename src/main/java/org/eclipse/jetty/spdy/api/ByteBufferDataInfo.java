package org.eclipse.jetty.spdy.api;

import java.nio.ByteBuffer;

public class ByteBufferDataInfo extends DataInfo
{
    private ByteBuffer buffer;

    public ByteBufferDataInfo(ByteBuffer buffer, boolean close)
    {
        this(buffer, close, false);
    }

    public ByteBufferDataInfo(ByteBuffer buffer, boolean close, boolean compress)
    {
        super(close, compress);
        setByteBuffer(buffer);
    }

    @Override
    public int getBytesCount()
    {
        return buffer.remaining();
    }

    @Override
    public int getBytes(ByteBuffer output)
    {
        int length = output.remaining();
        if (buffer.remaining() > length)
        {
            int limit = buffer.limit();
            buffer.limit(buffer.position() + length);
            output.put(buffer);
            buffer.limit(limit);
        }
        else
        {
            length = buffer.remaining();
            output.put(buffer);
        }
        setConsumed(!buffer.hasRemaining());
        return length;
    }

    public void setByteBuffer(ByteBuffer buffer)
    {
        this.buffer = buffer;
    }
}
