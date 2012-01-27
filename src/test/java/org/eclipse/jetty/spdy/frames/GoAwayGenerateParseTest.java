package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class GoAwayGenerateParseTest
{
    @Test
    public void testGenerateParse() throws Exception
    {
        short version = 2;
        int lastStreamId = 13;
        int statusCode = 1;
        GoAwayFrame frame1 = new GoAwayFrame(version, lastStreamId, statusCode);
        Generator generator = new Generator(new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        parser.parse(buffer);
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.GO_AWAY, frame2.getType());
        GoAwayFrame goAway = (GoAwayFrame)frame2;
        Assert.assertEquals(version, goAway.getVersion());
        Assert.assertEquals(lastStreamId, goAway.getLastStreamId());
        Assert.assertEquals(0, goAway.getFlags());
        Assert.assertEquals(statusCode, goAway.getStatusCode());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        short version = 2;
        int lastStreamId = 13;
        int statusCode = 1;
        GoAwayFrame frame1 = new GoAwayFrame(version, lastStreamId, statusCode);
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
        Assert.assertEquals(ControlFrameType.GO_AWAY, frame2.getType());
        GoAwayFrame goAway = (GoAwayFrame)frame2;
        Assert.assertEquals(version, goAway.getVersion());
        Assert.assertEquals(lastStreamId, goAway.getLastStreamId());
        Assert.assertEquals(0, goAway.getFlags());
        Assert.assertEquals(statusCode, goAway.getStatusCode());
    }
}
