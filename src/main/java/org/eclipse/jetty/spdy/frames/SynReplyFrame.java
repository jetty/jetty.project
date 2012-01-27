package org.eclipse.jetty.spdy.frames;

import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;

public class SynReplyFrame extends ControlFrame
{
    private final int streamId;
    private final Headers headers;

    public SynReplyFrame(short version, byte flags, int streamId, Headers headers)
    {
        super(version, ControlFrameType.SYN_REPLY, flags);
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
        return (getFlags() & ReplyInfo.FLAG_FIN) == ReplyInfo.FLAG_FIN;
    }

    @Override
    public String toString()
    {
        return super.toString() + " stream=" + getStreamId() + " close=" + isClose();
    }
}
