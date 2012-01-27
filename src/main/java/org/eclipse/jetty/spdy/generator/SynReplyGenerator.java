package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.SynReplyFrame;

public class SynReplyGenerator extends ControlFrameGenerator
{
    private final HeadersBlockGenerator headersBlockGenerator;

    public SynReplyGenerator(HeadersBlockGenerator headersBlockGenerator)
    {
        this.headersBlockGenerator = headersBlockGenerator;
    }

    @Override
    public ByteBuffer generate(ControlFrame frame) throws StreamException
    {
        SynReplyFrame synReply = (SynReplyFrame)frame;
        short version = synReply.getVersion();

        ByteBuffer headersBuffer = headersBlockGenerator.generate(version, synReply.getHeaders());

        int frameBodyLength = getFrameDataLength(version);

        int frameLength = frameBodyLength + headersBuffer.remaining();
        if (frameLength > 0xFF_FF_FF)
            throw new StreamException(StreamStatus.PROTOCOL_ERROR, "Too many headers");

        int totalLength = ControlFrame.HEADER_LENGTH + frameLength;

        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        generateControlFrameHeader(synReply, frameLength, buffer);

        buffer.putInt(synReply.getStreamId() & 0x7F_FF_FF_FF);
        writeAdditional(version, buffer);

        buffer.put(headersBuffer);

        buffer.flip();
        return buffer;
    }

    private int getFrameDataLength(short version) throws StreamException
    {
        switch (version)
        {
            case 2:
                return 6;
            case 3:
                return 4;
            default:
                // Here the version is trusted to be correct; if it's not
                // then it's a bug rather than an application error
                throw new IllegalStateException();
        }
    }

    private void writeAdditional(short version, ByteBuffer buffer) throws StreamException
    {
        switch (version)
        {
            case 2:
                buffer.putShort((short)0);
                break;
            case 3:
                break;
            default:
                // Here the version is trusted to be correct; if it's not
                // then it's a bug rather than an application error
                throw new IllegalStateException();
        }
    }
}
