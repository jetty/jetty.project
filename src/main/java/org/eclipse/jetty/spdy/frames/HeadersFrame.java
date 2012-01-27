package org.eclipse.jetty.spdy.frames;

import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.HeadersInfo;

public class HeadersFrame extends ControlFrame
{
    private final int streamId;
    private final Headers headers;

    public HeadersFrame(short version, byte flags, int streamId, Headers headers)
    {
        super(version, ControlFrameType.HEADERS, flags);
        this.streamId = streamId;
        this.headers = headers;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public Headers getHeaders()
    {
        return headers;
    }

    public boolean isClose()
    {
        return (getFlags() & HeadersInfo.FLAG_FIN) == HeadersInfo.FLAG_FIN;
    }

    public boolean isResetCompression()
    {
        return (getFlags() & HeadersInfo.FLAG_RESET_COMPRESSION) == HeadersInfo.FLAG_RESET_COMPRESSION;
    }

    @Override
    public String toString()
    {
        return super.toString() + " stream=" + getStreamId() + " close=" + isClose() + " reset_compression=" + isResetCompression();
    }
}
