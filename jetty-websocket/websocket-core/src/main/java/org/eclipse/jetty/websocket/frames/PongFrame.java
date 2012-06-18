package org.eclipse.jetty.websocket.frames;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.5.3">Pong Frame (0x0A)</a>.
 */
public class PongFrame extends ControlFrame
{
    private final int pingId;

    public PongFrame(int pingId)
    {
        super(ControlFrameType.PONG_FRAME);
        this.pingId = pingId;
    }

    public int getPingId()
    {
        return pingId;
    }

    @Override
    public String toString()
    {
        return String.format("%s pong=%d",super.toString(),getPingId());
    }
}
