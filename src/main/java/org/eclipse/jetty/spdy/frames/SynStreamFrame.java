package org.eclipse.jetty.spdy.frames;

import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.SynInfo;

public class SynStreamFrame extends ControlFrame
{
    private final int streamId;
    private final int associatedStreamId;
    private final byte priority;
    private final Headers headers;

    public SynStreamFrame(short version, byte flags, int streamId, int associatedStreamId, byte priority, Headers headers)
    {
        super(version, ControlFrameType.SYN_STREAM, flags);
        this.streamId = streamId;
        this.associatedStreamId = associatedStreamId;
        this.priority = priority;
        this.headers = headers;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public int getAssociatedStreamId()
    {
        return associatedStreamId;
    }

    public byte getPriority()
    {
        return priority;
    }

    public Headers getHeaders()
    {
        return headers;
    }

    public boolean isClose()
    {
        return (getFlags() & SynInfo.FLAG_FIN) == SynInfo.FLAG_FIN;
    }

    public boolean isUnidirectional()
    {
        return (getFlags() & SynInfo.FLAG_UNIDIRECTIONAL) == SynInfo.FLAG_UNIDIRECTIONAL;
    }

    @Override
    public String toString()
    {
        return super.toString() + " stream=" + getStreamId() + " close=" + isClose();
    }
}
