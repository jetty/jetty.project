package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class RstStreamGenerateParseTest
{
    @Test
    public void testGenerateParse() throws Exception
    {
        short version = 2;
        int streamId = 13;
        int streamStatus = StreamStatus.UNSUPPORTED_VERSION.getCode(version);
        RstStreamFrame frame1 = new RstStreamFrame(version, streamId, streamStatus);
        Generator generator = new Generator(new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        parser.parse(buffer);
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.RST_STREAM, frame2.getType());
        RstStreamFrame rstStream = (RstStreamFrame)frame2;
        Assert.assertEquals(version, rstStream.getVersion());
        Assert.assertEquals(streamId, rstStream.getStreamId());
        Assert.assertEquals(0, rstStream.getFlags());
        Assert.assertEquals(streamStatus, rstStream.getStatusCode());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        short version = 2;
        int streamId = 13;
        int streamStatus = StreamStatus.UNSUPPORTED_VERSION.getCode(version);
        RstStreamFrame frame1 = new RstStreamFrame(version, streamId, streamStatus);
        Generator generator = new Generator(new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        while (buffer.hasRemaining())
            parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.RST_STREAM, frame2.getType());
        RstStreamFrame rstStream = (RstStreamFrame)frame2;
        Assert.assertEquals(version, rstStream.getVersion());
        Assert.assertEquals(streamId, rstStream.getStreamId());
        Assert.assertEquals(0, rstStream.getFlags());
        Assert.assertEquals(streamStatus, rstStream.getStatusCode());
    }
}
