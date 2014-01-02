//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class DataGenerateParseTest
{
    @Test
    public void testGenerateParse() throws Exception
    {
        testGenerateParse("test1");
    }

    @Test
    public void testGenerateParseZeroLength() throws Exception
    {
        testGenerateParse("");
    }

    private void testGenerateParse(String content) throws Exception
    {
        int length = content.length();
        DataInfo data = new StringDataInfo(content, true);
        int streamId = 13;
        Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.data(streamId, 2 * length, data);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        parser.parse(buffer);
        DataFrame frame2 = listener.getDataFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(streamId, frame2.getStreamId());
        Assert.assertEquals(DataInfo.FLAG_CLOSE, frame2.getFlags());
        Assert.assertEquals(length, frame2.getLength());
        Assert.assertEquals(length, listener.getData().remaining());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        String content = "test2";
        int length = content.length();
        DataInfo data = new StringDataInfo(content, true);
        int streamId = 13;
        Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.data(streamId, 2 * length, data);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        while (buffer.hasRemaining())
        {
            parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
            if (buffer.remaining() < length)
            {
                DataFrame frame2 = listener.getDataFrame();
                Assert.assertNotNull(frame2);
                Assert.assertEquals(streamId, frame2.getStreamId());
                Assert.assertEquals(buffer.hasRemaining() ? 0 : DataInfo.FLAG_CLOSE, frame2.getFlags());
                Assert.assertEquals(1, frame2.getLength());
                Assert.assertEquals(1, listener.getData().remaining());
            }
        }
    }

    @Test
    public void testGenerateParseWithSyntheticFrames() throws Exception
    {
        String content = "0123456789ABCDEF";
        int length = content.length();
        DataInfo data = new StringDataInfo(content, true);
        int streamId = 13;
        Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.data(streamId, 2 * length, data);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);

        // Split the buffer to simulate a split boundary in receiving the bytes
        int split = 3;
        ByteBuffer buffer1 = ByteBuffer.allocate(buffer.remaining() - split);
        buffer.limit(buffer.limit() - split);
        buffer1.put(buffer);
        buffer1.flip();
        ByteBuffer buffer2 = ByteBuffer.allocate(split);
        buffer.limit(buffer.limit() + split);
        buffer2.put(buffer);
        buffer2.flip();

        parser.parse(buffer1);
        DataFrame frame2 = listener.getDataFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(streamId, frame2.getStreamId());
        Assert.assertEquals(0, frame2.getFlags());
        Assert.assertEquals(length - split, frame2.getLength());
        Assert.assertEquals(length - split, listener.getData().remaining());

        parser.parse(buffer2);
        DataFrame frame3 = listener.getDataFrame();

        Assert.assertNotNull(frame3);
        Assert.assertEquals(streamId, frame3.getStreamId());
        Assert.assertEquals(DataInfo.FLAG_CLOSE, frame3.getFlags());
        Assert.assertEquals(split, frame3.getLength());
        Assert.assertEquals(split, listener.getData().remaining());
    }
}
