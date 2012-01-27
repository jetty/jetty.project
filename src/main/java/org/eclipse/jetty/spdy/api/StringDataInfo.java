package org.eclipse.jetty.spdy.api;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

public class StringDataInfo extends DataInfo
{
    private byte[] bytes;
    private int offset;

    public StringDataInfo(String string, boolean close)
    {
        this(string, close, false);
    }

    public StringDataInfo(String string, boolean close, boolean compress)
    {
        super(close, compress);
        setString(string);
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

    public void setString(String string)
    {
        this.bytes = string.getBytes(Charset.forName("UTF-8"));
        this.offset = 0;
    }
}
