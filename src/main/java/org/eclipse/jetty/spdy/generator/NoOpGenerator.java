package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.NoOpFrame;

public class NoOpGenerator extends ControlFrameGenerator
{
    @Override
    public ByteBuffer generate(ControlFrame frame) throws StreamException
    {
        NoOpFrame noOp = (NoOpFrame)frame;

        int frameBodyLength = 0;
        int totalLength = ControlFrame.HEADER_LENGTH + frameBodyLength;
        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        generateControlFrameHeader(noOp, frameBodyLength, buffer);

        buffer.flip();
        return buffer;
    }
}
