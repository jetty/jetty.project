package org.eclipse.jetty.spdy.frames;

import org.eclipse.jetty.spdy.api.SessionStatus;

public class GoAwayFrame extends ControlFrame
{
    private final int lastStreamId;
    private final int statusCode;

    public GoAwayFrame(short version, int lastStreamId, int statusCode)
    {
        super(version, ControlFrameType.GO_AWAY, (byte)0);
        this.lastStreamId = lastStreamId;
        this.statusCode = statusCode;
    }

    public int getLastStreamId()
    {
        return lastStreamId;
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    @Override
    public String toString()
    {
        SessionStatus sessionStatus = SessionStatus.from(getStatusCode());
        return super.toString() + " last_stream=" + getLastStreamId() + " status=" + (sessionStatus == null ? getStatusCode() : sessionStatus);
    }
}
