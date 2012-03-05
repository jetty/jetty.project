/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StandardByteBufferPool;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class SynReplyGenerateParseTest
{
    @Test
    public void testGenerateParse() throws Exception
    {
        byte flags = ReplyInfo.FLAG_CLOSE;
        int streamId = 13;
        Headers headers = new Headers();
        headers.put("a", "b");
        SynReplyFrame frame1 = new SynReplyFrame(SPDY.V2, flags, streamId, headers);
        Generator generator = new Generator(new StandardByteBufferPool(), new StandardCompressionFactory().newCompressor());
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
        Assert.assertEquals(SPDY.V2, synReply.getVersion());
        Assert.assertEquals(flags, synReply.getFlags());
        Assert.assertEquals(streamId, synReply.getStreamId());
        Assert.assertEquals(headers, synReply.getHeaders());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        byte flags = ReplyInfo.FLAG_CLOSE;
        int streamId = 13;
        Headers headers = new Headers();
        headers.put("a", "b");
        SynReplyFrame frame1 = new SynReplyFrame(SPDY.V2, flags, streamId, headers);
        Generator generator = new Generator(new StandardByteBufferPool(), new StandardCompressionFactory().newCompressor());
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
        Assert.assertEquals(SPDY.V2, synReply.getVersion());
        Assert.assertEquals(flags, synReply.getFlags());
        Assert.assertEquals(streamId, synReply.getStreamId());
        Assert.assertEquals(headers, synReply.getHeaders());
    }
}
