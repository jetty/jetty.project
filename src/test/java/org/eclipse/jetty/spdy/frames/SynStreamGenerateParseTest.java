package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class SynStreamGenerateParseTest
{
    @Test
    public void testGenerateParse() throws Exception
    {
        short version = 2;
        byte flags = SynInfo.FLAG_FIN;
        int streamId = 13;
        int associatedStreamId = 11;
        byte priority = 3;
        Headers headers = new Headers();
        headers.put("a", "b");
        headers.put("c", "d");
        SynStreamFrame frame1 = new SynStreamFrame(version, flags, streamId, associatedStreamId, priority, headers);
        Generator generator = new Generator(new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        parser.parse(buffer);
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.SYN_STREAM, frame2.getType());
        SynStreamFrame synStream = (SynStreamFrame)frame2;
        Assert.assertEquals(version, synStream.getVersion());
        Assert.assertEquals(streamId, synStream.getStreamId());
        Assert.assertEquals(associatedStreamId, synStream.getAssociatedStreamId());
        Assert.assertEquals(flags, synStream.getFlags());
        Assert.assertEquals(priority, synStream.getPriority());
        Assert.assertEquals(headers, synStream.getHeaders());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        short version = 2;
        byte flags = SynInfo.FLAG_FIN;
        int streamId = 13;
        int associatedStreamId = 11;
        byte priority = 3;
        Headers headers = new Headers();
        headers.put("a", "b");
        headers.put("c", "d");
        SynStreamFrame frame1 = new SynStreamFrame(version, flags, streamId, associatedStreamId, priority, headers);
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
        Assert.assertEquals(ControlFrameType.SYN_STREAM, frame2.getType());
        SynStreamFrame synStream = (SynStreamFrame)frame2;
        Assert.assertEquals(version, synStream.getVersion());
        Assert.assertEquals(streamId, synStream.getStreamId());
        Assert.assertEquals(associatedStreamId, synStream.getAssociatedStreamId());
        Assert.assertEquals(flags, synStream.getFlags());
        Assert.assertEquals(priority, synStream.getPriority());
        Assert.assertEquals(headers, synStream.getHeaders());
    }
}
