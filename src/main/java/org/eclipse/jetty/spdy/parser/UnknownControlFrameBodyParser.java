package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;

public class UnknownControlFrameBodyParser extends ControlFrameBodyParser
{
    private int remaining;

    public UnknownControlFrameBodyParser(ControlFrameParser controlFrameParser)
    {
        this.remaining = controlFrameParser.getLength();
    }

    @Override
    public boolean parse(ByteBuffer buffer) throws StreamException
    {
        int consumed = Math.min(remaining, buffer.remaining());
        buffer.position(buffer.position() + consumed);
        remaining -= consumed;
        return remaining == 0;
    }
}
