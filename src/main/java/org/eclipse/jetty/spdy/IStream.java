package org.eclipse.jetty.spdy;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.DataFrame;

public interface IStream extends Stream
{
    public int getWindowSize();

    public void updateWindowSize(int delta);

    public void setFrameListener(FrameListener frameListener);

    public void updateCloseState(boolean close);

    public void handle(ControlFrame frame);

    public void handle(DataFrame dataFrame, ByteBuffer data);
}
