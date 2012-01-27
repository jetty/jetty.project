package org.eclipse.jetty.spdy.frames;

import org.eclipse.jetty.spdy.api.StreamStatus;

public class RstStreamFrame extends ControlFrame
{
    private final int streamId;
    private final int statusCode;

    public RstStreamFrame(short version, int streamId, int statusCode)
    {
        super(version, ControlFrameType.RST_STREAM, (byte)0);
        this.streamId = streamId;
        this.statusCode = statusCode;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    @Override
    public String toString()
    {
        StreamStatus streamStatus = StreamStatus.from(getVersion(), getStatusCode());
        return super.toString() + " stream=" + getStreamId() + " status=" + (streamStatus == null ? getStatusCode() : streamStatus);
    }
}
