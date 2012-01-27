package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class WindowUpdateGenerateParseTest
{
    @Test
    public void testGenerateParse() throws Exception
    {
        short version = 2;
        int streamId = 13;
        int windowDelta = 17;
        WindowUpdateFrame frame1 = new WindowUpdateFrame(version, streamId, windowDelta);
        Generator generator = new Generator(new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        parser.parse(buffer);
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.WINDOW_UPDATE, frame2.getType());
        WindowUpdateFrame windowUpdate = (WindowUpdateFrame)frame2;
        Assert.assertEquals(version, windowUpdate.getVersion());
        Assert.assertEquals(streamId, windowUpdate.getStreamId());
        Assert.assertEquals(0, windowUpdate.getFlags());
        Assert.assertEquals(windowDelta, windowUpdate.getWindowDelta());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        short version = 2;
        int streamId = 13;
        int windowDelta = 17;
        WindowUpdateFrame frame1 = new WindowUpdateFrame(version, streamId, windowDelta);
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
        Assert.assertEquals(ControlFrameType.WINDOW_UPDATE, frame2.getType());
        WindowUpdateFrame windowUpdate = (WindowUpdateFrame)frame2;
        Assert.assertEquals(version, windowUpdate.getVersion());
        Assert.assertEquals(streamId, windowUpdate.getStreamId());
        Assert.assertEquals(0, windowUpdate.getFlags());
        Assert.assertEquals(windowDelta, windowUpdate.getWindowDelta());
    }
}
