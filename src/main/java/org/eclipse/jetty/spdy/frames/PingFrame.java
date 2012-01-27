package org.eclipse.jetty.spdy.frames;

public class PingFrame extends ControlFrame
{
    private final int pingId;

    public PingFrame(short version, int pingId)
    {
        super(version, ControlFrameType.PING, (byte)0);
        this.pingId = pingId;
    }

    public int getPingId()
    {
        return pingId;
    }

    @Override
    public String toString()
    {
        return super.toString() + " ping=" + getPingId();
    }
}
