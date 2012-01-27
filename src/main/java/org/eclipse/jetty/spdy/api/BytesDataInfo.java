package org.eclipse.jetty.spdy.api;

import java.nio.ByteBuffer;

public class BytesDataInfo extends DataInfo
{
    private byte[] bytes;
    private int offset;

    public BytesDataInfo(byte[] bytes, boolean close)
    {
        this(bytes, close, false);
    }

    public BytesDataInfo(byte[] bytes, boolean close, boolean compress)
    {
        super(close, compress);
        setBytes(bytes);
    }

    @Override
    public int getBytesCount()
    {
        return bytes.length - offset;
    }

    @Override
    public int getBytes(ByteBuffer output)
    {
        int remaining = output.remaining();
        int length = Math.min(bytes.length - offset, remaining);
        output.put(bytes, offset, length);
        offset += length;
        if (offset == bytes.length)
            setConsumed(true);
        return length;
    }

    public void setBytes(byte[] bytes)
    {
        this.bytes = bytes;
        this.offset = 0;
    }
}
