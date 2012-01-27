package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.RstStreamFrame;

public class RstStreamGenerator extends ControlFrameGenerator
{
    @Override
    public ByteBuffer generate(ControlFrame frame) throws StreamException
    {
        RstStreamFrame rstStream = (RstStreamFrame)frame;

        int frameBodyLength = 8;
        int totalLength = ControlFrame.HEADER_LENGTH + frameBodyLength;
        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        generateControlFrameHeader(rstStream, frameBodyLength, buffer);

        buffer.putInt(rstStream.getStreamId() & 0x7F_FF_FF_FF);
        buffer.putInt(rstStream.getStatusCode());

        buffer.flip();
        return buffer;
    }
}
