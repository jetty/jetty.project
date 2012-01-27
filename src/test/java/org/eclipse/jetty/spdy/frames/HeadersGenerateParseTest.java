package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class HeadersGenerateParseTest
{
    @Test
    public void testGenerateParse() throws Exception
    {
        short version = 2;
        byte flags = HeadersInfo.FLAG_RESET_COMPRESSION;
        int streamId = 13;
        Headers headers = new Headers();
        headers.put("a", "b");
        HeadersFrame frame1 = new HeadersFrame(version, flags, streamId, headers);
        Generator generator = new Generator(new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        parser.parse(buffer);
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.HEADERS, frame2.getType());
        HeadersFrame headersFrame = (HeadersFrame)frame2;
        Assert.assertEquals(version, headersFrame.getVersion());
        Assert.assertEquals(streamId, headersFrame.getStreamId());
        Assert.assertEquals(flags, headersFrame.getFlags());
        Assert.assertEquals(headers, headersFrame.getHeaders());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        short version = 2;
        byte flags = HeadersInfo.FLAG_RESET_COMPRESSION;
        int streamId = 13;
        Headers headers = new Headers();
        headers.put("a", "b");
        HeadersFrame frame1 = new HeadersFrame(version, flags, streamId, headers);
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
        Assert.assertEquals(ControlFrameType.HEADERS, frame2.getType());
        HeadersFrame headersFrame = (HeadersFrame)frame2;
        Assert.assertEquals(version, headersFrame.getVersion());
        Assert.assertEquals(streamId, headersFrame.getStreamId());
        Assert.assertEquals(flags, headersFrame.getFlags());
        Assert.assertEquals(headers, headersFrame.getHeaders());
    }
}
