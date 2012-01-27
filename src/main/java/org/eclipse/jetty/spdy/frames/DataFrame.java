package org.eclipse.jetty.spdy.frames;

import org.eclipse.jetty.spdy.api.DataInfo;

public class DataFrame
{
    public static final int HEADER_LENGTH = 8;

    private final int streamId;
    private final byte flags;
    private final int length;

    public DataFrame(int streamId, byte flags, int length)
    {
        this.streamId = streamId;
        this.flags = flags;
        this.length = length;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public byte getFlags()
    {
        return flags;
    }

    public int getLength()
    {
        return length;
    }

    public boolean isClose()
    {
        return (flags & DataInfo.FLAG_FIN) == DataInfo.FLAG_FIN;
    }

    public boolean isCompress()
    {
        return (flags & DataInfo.FLAG_COMPRESS) == DataInfo.FLAG_COMPRESS;
    }

    @Override
    public String toString()
    {
        return "DATA stream=" + getStreamId() + " length=" + getLength() + " close=" + isClose() + " compress=" + isCompress();
    }
}
