package org.eclipse.jetty.spdy;

import org.eclipse.jetty.spdy.api.StreamStatus;

public class StreamException extends Exception
{
    private final StreamStatus streamStatus;

    public StreamException(StreamStatus streamStatus)
    {
        this.streamStatus = streamStatus;
    }

    public StreamException(StreamStatus streamStatus, String message)
    {
        super(message);
        this.streamStatus = streamStatus;
    }

    public StreamException(StreamStatus streamStatus, Throwable x)
    {
        super(x);
        this.streamStatus = streamStatus;
    }

    public StreamStatus getStreamStatus()
    {
        return streamStatus;
    }
}
