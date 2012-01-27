package org.eclipse.jetty.spdy.api;

import java.nio.ByteBuffer;

public abstract class DataInfo
{
    public final static byte FLAG_FIN = 1;
    public final static byte FLAG_COMPRESS = 2;

    private boolean close;
    private boolean compress;
    private boolean consumed;

    public DataInfo(boolean close)
    {
        setClose(close);
    }

    public DataInfo(boolean close, boolean compress)
    {
        setClose(close);
        setCompress(compress);
    }

    public boolean isCompress()
    {
        return compress;
    }

    public void setCompress(boolean compress)
    {
        this.compress = compress;
    }

    public boolean isClose()
    {
        return close;
    }

    public void setClose(boolean close)
    {
        this.close = close;
    }

    public byte getFlags()
    {
        byte flags = isClose() ? FLAG_FIN : 0;
        flags |= isCompress() ? FLAG_COMPRESS : 0;
        return flags;
    }

    public abstract int getBytesCount();

    public abstract int getBytes(ByteBuffer output);

    public boolean isConsumed()
    {
        return consumed;
    }

    protected void setConsumed(boolean consumed)
    {
        this.consumed = consumed;
    }

    @Override
    public String toString()
    {
        return "DATA length=" + getBytesCount() + " close=" + isClose() + " compress=" + isCompress();
    }
}
