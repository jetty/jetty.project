package org.eclipse.jetty.websocket.frames;

import org.eclipse.jetty.websocket.frames.ControlFrameType;

public class PingFrame extends ControlFrame
{
    private final int pingId;

    public PingFrame(short version, int pingId)
    {
        super(version, ControlFrameType.PING_FRAME, (byte)0);
        this.pingId = pingId;
    }

    public int getPingId()
    {
        return pingId;
    }

    @Override
    public String toString()
    {
        return String.format("%s ping=%d", super.toString(), getPingId());
    }
}
