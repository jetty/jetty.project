package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.SessionException;
import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.parser.Parser;

public class TestSPDYParserListener implements Parser.Listener
{
    private ControlFrame controlFrame;

    @Override
    public void onControlFrame(ControlFrame frame)
    {
        this.controlFrame = frame;
    }

    @Override
    public void onDataFrame(DataFrame frame, ByteBuffer data)
    {
    }

    @Override
    public void onStreamException(StreamException x)
    {
    }

    @Override
    public void onSessionException(SessionException x)
    {
    }

    public ControlFrame getControlFrame()
    {
        return controlFrame;
    }
}
