package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class SynReplyGenerateParseTest
{
    @Test
    public void testGenerateParse() throws Exception
    {
        short version = 2;
        byte flags = ReplyInfo.FLAG_FIN;
        int streamId = 13;
        Headers headers = new Headers();
        headers.put("a", "b");
        SynReplyFrame frame1 = new SynReplyFrame(version, flags, streamId, headers);
        Generator generator = new Generator(new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        parser.parse(buffer);
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.SYN_REPLY, frame2.getType());
        SynReplyFrame synReply = (SynReplyFrame)frame2;
        Assert.assertEquals(version, synReply.getVersion());
        Assert.assertEquals(flags, synReply.getFlags());
        Assert.assertEquals(streamId, synReply.getStreamId());
        Assert.assertEquals(headers, synReply.getHeaders());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        short version = 2;
        byte flags = ReplyInfo.FLAG_FIN;
        int streamId = 13;
        Headers headers = new Headers();
        headers.put("a", "b");
        SynReplyFrame frame1 = new SynReplyFrame(version, flags, streamId, headers);
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
        Assert.assertEquals(ControlFrameType.SYN_REPLY, frame2.getType());
        SynReplyFrame synReply = (SynReplyFrame)frame2;
        Assert.assertEquals(version, synReply.getVersion());
        Assert.assertEquals(flags, synReply.getFlags());
        Assert.assertEquals(streamId, synReply.getStreamId());
        Assert.assertEquals(headers, synReply.getHeaders());
    }
}
