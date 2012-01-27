package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;

public class SynStreamGenerator extends ControlFrameGenerator
{
    private final HeadersBlockGenerator headersBlockGenerator;

    public SynStreamGenerator(HeadersBlockGenerator headersBlockGenerator)
    {
        this.headersBlockGenerator = headersBlockGenerator;
    }

    @Override
    public ByteBuffer generate(ControlFrame frame) throws StreamException
    {
        SynStreamFrame synStream = (SynStreamFrame)frame;
        short version = synStream.getVersion();

        ByteBuffer headersBuffer = headersBlockGenerator.generate(version, synStream.getHeaders());

        int frameBodyLength = 10;

        int frameLength = frameBodyLength + headersBuffer.remaining();
        if (frameLength > 0xFF_FF_FF)
            throw new StreamException(StreamStatus.PROTOCOL_ERROR, "Too many headers");

        int totalLength = ControlFrame.HEADER_LENGTH + frameLength;

        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        generateControlFrameHeader(synStream, frameLength, buffer);

        buffer.putInt(synStream.getStreamId() & 0x7F_FF_FF_FF);
        buffer.putInt(synStream.getAssociatedStreamId() & 0x7F_FF_FF_FF);
        writePriority(version, synStream.getPriority(), buffer);

        buffer.put(headersBuffer);

        buffer.flip();
        return buffer;
    }

    private void writePriority(short version, byte priority, ByteBuffer buffer) throws StreamException
    {
        switch (version)
        {
            case 2:
                priority <<= 6;
                break;
            case 3:
                priority <<= 5;
                break;
            default:
                throw new StreamException(StreamStatus.UNSUPPORTED_VERSION);
        }
        buffer.put(priority);
        buffer.put((byte)0);
    }
}
