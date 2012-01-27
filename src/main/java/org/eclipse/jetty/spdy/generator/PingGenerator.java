package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.PingFrame;

public class PingGenerator extends ControlFrameGenerator
{
    @Override
    public ByteBuffer generate(ControlFrame frame) throws StreamException
    {
        PingFrame ping = (PingFrame)frame;

        int frameBodyLength = 4;
        int totalLength = ControlFrame.HEADER_LENGTH + frameBodyLength;
        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        generateControlFrameHeader(ping, frameBodyLength, buffer);

        buffer.putInt(ping.getPingId());

        buffer.flip();
        return buffer;
    }
}
