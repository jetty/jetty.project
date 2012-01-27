package org.eclipse.jetty.spdy.frames;

public class NoOpFrame extends ControlFrame
{
    public NoOpFrame()
    {
        super((short)2, ControlFrameType.NOOP, (byte)0);
    }
}
