package org.eclipse.jetty.spdy.api;

public class RstInfo
{
    private final int streamId;
    private final StreamStatus streamStatus;

    public RstInfo(int streamId, StreamStatus streamStatus)
    {
        this.streamId = streamId;
        this.streamStatus = streamStatus;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public StreamStatus getStreamStatus()
    {
        return streamStatus;
    }
}
