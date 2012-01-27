package org.eclipse.jetty.spdy.api;

public class PingInfo
{
    private final int pingId;

    public PingInfo(int pingId)
    {
        this.pingId = pingId;
    }

    public int getPingId()
    {
        return pingId;
    }
}
