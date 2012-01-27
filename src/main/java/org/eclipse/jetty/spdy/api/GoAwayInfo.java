package org.eclipse.jetty.spdy.api;

public class GoAwayInfo
{
    private final int lastStreamId;
    private final SessionStatus sessionStatus;

    public GoAwayInfo(int lastStreamId, SessionStatus sessionStatus)
    {
        this.lastStreamId = lastStreamId;
        this.sessionStatus = sessionStatus;
    }

    public int getLastStreamId()
    {
        return lastStreamId;
    }

    public SessionStatus getSessionStatus()
    {
        return sessionStatus;
    }
}
