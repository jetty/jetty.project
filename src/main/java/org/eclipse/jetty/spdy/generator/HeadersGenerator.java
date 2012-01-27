package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.HeadersFrame;

public class HeadersGenerator extends ControlFrameGenerator
{
    private final HeadersBlockGenerator headersBlockGenerator;

    public HeadersGenerator(HeadersBlockGenerator headersBlockGenerator)
    {
        this.headersBlockGenerator = headersBlockGenerator;
    }

    @Override
    public ByteBuffer generate(ControlFrame frame) throws StreamException
    {
        HeadersFrame headers = (HeadersFrame)frame;
        short version = headers.getVersion();

        ByteBuffer headersBuffer = headersBlockGenerator.generate(version, headers.getHeaders());

        int frameBodyLength = 4;

        int frameLength = frameBodyLength + headersBuffer.remaining();
        if (frameLength > 0xFF_FF_FF)
            throw new StreamException(StreamStatus.PROTOCOL_ERROR, "Too many headers");

        int totalLength = ControlFrame.HEADER_LENGTH + frameLength;

        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        generateControlFrameHeader(headers, frameLength, buffer);

        buffer.putInt(headers.getStreamId() & 0x7F_FF_FF_FF);

        buffer.put(headersBuffer);

        buffer.flip();
        return buffer;
    }
}
