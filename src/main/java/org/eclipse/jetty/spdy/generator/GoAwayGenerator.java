package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.GoAwayFrame;

public class GoAwayGenerator extends ControlFrameGenerator
{
    @Override
    public ByteBuffer generate(ControlFrame frame) throws StreamException
    {
        GoAwayFrame goAway = (GoAwayFrame)frame;

        int frameBodyLength = 8;
        int totalLength = ControlFrame.HEADER_LENGTH + frameBodyLength;
        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        generateControlFrameHeader(goAway, frameBodyLength, buffer);

        buffer.putInt(goAway.getLastStreamId() & 0x7F_FF_FF_FF);
        buffer.putInt(goAway.getStatusCode());

        buffer.flip();
        return buffer;
    }
}
