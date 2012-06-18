package org.eclipse.jetty.websocket.frames;

import org.eclipse.jetty.websocket.frames.ControlFrameType;

public class PongFrame extends ControlFrame
{
    private final int pingId;

    public PongFrame(short version, int pingId)
    {
        super(version, ControlFrameType.PONG_FRAME, (byte)0);
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
