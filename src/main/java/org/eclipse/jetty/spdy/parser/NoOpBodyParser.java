package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.frames.NoOpFrame;

public class NoOpBodyParser extends ControlFrameBodyParser
{
    private final ControlFrameParser controlFrameParser;

    public NoOpBodyParser(ControlFrameParser controlFrameParser)
    {
        this.controlFrameParser = controlFrameParser;
    }

    @Override
    public boolean parse(ByteBuffer buffer) throws StreamException
    {
        NoOpFrame frame = new NoOpFrame();
        controlFrameParser.onControlFrame(frame);
        return true;
    }
}
