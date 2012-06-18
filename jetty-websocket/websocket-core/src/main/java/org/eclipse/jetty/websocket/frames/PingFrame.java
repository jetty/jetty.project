package org.eclipse.jetty.websocket.frames;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.5.2">Ping Frame (0x09)</a>.
 */
public class PingFrame extends ControlFrame
{
    private final int pingId;

    public PingFrame(int pingId)
    {
        super(ControlFrameType.PING_FRAME);
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
