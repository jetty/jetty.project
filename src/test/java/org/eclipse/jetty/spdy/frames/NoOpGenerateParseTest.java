package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class NoOpGenerateParseTest
{
    @Test
    public void testGenerateParse() throws Exception
    {
        NoOpFrame frame1 = new NoOpFrame();
        Generator generator = new Generator(new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        parser.parse(buffer);
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.NOOP, frame2.getType());
        NoOpFrame noOp = (NoOpFrame)frame2;
        Assert.assertEquals(0, noOp.getFlags());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        NoOpFrame frame1 = new NoOpFrame();
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
        Assert.assertEquals(ControlFrameType.NOOP, frame2.getType());
        NoOpFrame noOp = (NoOpFrame)frame2;
        Assert.assertEquals(0, noOp.getFlags());
    }
}
