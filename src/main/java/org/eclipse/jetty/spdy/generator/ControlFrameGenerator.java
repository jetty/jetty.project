package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.frames.ControlFrame;

public abstract class ControlFrameGenerator
{
    public abstract ByteBuffer generate(ControlFrame frame) throws StreamException;

    protected void generateControlFrameHeader(ControlFrame frame, int frameLength, ByteBuffer buffer)
    {
        buffer.putShort((short)(0x8000 + frame.getVersion()));
        buffer.putShort(frame.getType().getCode());
        int flagsAndLength = frame.getFlags();
        flagsAndLength <<= 24;
        flagsAndLength += frameLength;
        buffer.putInt(flagsAndLength);
    }
}
