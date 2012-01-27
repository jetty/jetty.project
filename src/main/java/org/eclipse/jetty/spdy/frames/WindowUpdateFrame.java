package org.eclipse.jetty.spdy.frames;

public class WindowUpdateFrame extends ControlFrame
{
    private final int streamId;
    private final int windowDelta;

    public WindowUpdateFrame(short version, int streamId, int windowDelta)
    {
        super(version, ControlFrameType.WINDOW_UPDATE, (byte)0);
        this.streamId = streamId;
        this.windowDelta = windowDelta;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public int getWindowDelta()
    {
        return windowDelta;
    }

    @Override
    public String toString()
    {
        return super.toString() + " stream=" + getStreamId() + " delta=" + getWindowDelta();
    }
}
