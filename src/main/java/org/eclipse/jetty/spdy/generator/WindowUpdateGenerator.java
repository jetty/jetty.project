package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.WindowUpdateFrame;

public class WindowUpdateGenerator extends ControlFrameGenerator
{
    @Override
    public ByteBuffer generate(ControlFrame frame) throws StreamException
    {
        WindowUpdateFrame windowUpdate = (WindowUpdateFrame)frame;

        int frameBodyLength = 8;
        int totalLength = ControlFrame.HEADER_LENGTH + frameBodyLength;
        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        generateControlFrameHeader(windowUpdate, frameBodyLength, buffer);

        buffer.putInt(windowUpdate.getStreamId() & 0x7F_FF_FF_FF);
        buffer.putInt(windowUpdate.getWindowDelta() & 0x7F_FF_FF_FF);

        buffer.flip();
        return buffer;
    }
}
