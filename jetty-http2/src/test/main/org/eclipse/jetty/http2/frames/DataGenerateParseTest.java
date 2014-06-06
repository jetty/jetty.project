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

package org.eclipse.jetty.http2.frames;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.Assert;
import org.junit.Test;

public class DataGenerateParseTest
{
    private final byte[] smallContent = new byte[128];
    private final byte[] largeContent = new byte[128 * 1024];
    private final ByteBufferPool byteBufferPool = new MappedByteBufferPool();

    public DataGenerateParseTest()
    {
        Random random = new Random();
        random.nextBytes(smallContent);
        random.nextBytes(largeContent);
    }

    @Test
    public void testGenerateParseNoContentNoPadding()
    {
        ByteBuffer content = BufferUtil.EMPTY_BUFFER;
        List<DataFrame> frames = testGenerateParse(0, content);
        Assert.assertEquals(1, frames.size());
        DataFrame frame = frames.get(0);
        Assert.assertTrue(frame.getStreamId() != 0);
        Assert.assertTrue(frame.isEnd());
        Assert.assertEquals(content, frame.getData());
    }

    @Test
    public void testGenerateParseSmallContentNoPadding()
    {
        testGenerateParseSmallContent(0);
    }

    @Test
    public void testGenerateParseSmallContentSmallPadding()
    {
        testGenerateParseSmallContent(128);
    }

    @Test
    public void testGenerateParseSmallContentLargePadding()
    {
        testGenerateParseSmallContent(1024);
    }

    private void testGenerateParseSmallContent(int paddingLength)
    {
        ByteBuffer content = ByteBuffer.wrap(smallContent);
        List<DataFrame> frames = testGenerateParse(paddingLength, content);
        Assert.assertEquals(1, frames.size());
        DataFrame frame = frames.get(0);
        Assert.assertTrue(frame.getStreamId() != 0);
        Assert.assertTrue(frame.isEnd());
        Assert.assertEquals(content, frame.getData());
    }

    @Test
    public  void testGenerateParseLargeContent()
    {
        testGenerateParseLargeContent(0);
    }

    @Test
    public void testGenerateParseLargeContentSmallPadding()
    {
        testGenerateParseLargeContent(128);
    }

    @Test
    public void testGenerateParseLargeContentLargePadding()
    {
        testGenerateParseLargeContent(1024);
    }

    private void testGenerateParseLargeContent(int paddingLength)
    {
        ByteBuffer content = ByteBuffer.wrap(largeContent);
        List<DataFrame> frames = testGenerateParse(paddingLength, content);
        Assert.assertEquals(9, frames.size());
        ByteBuffer aggregate = ByteBuffer.allocate(content.remaining());
        for (int i = 1; i <= frames.size(); ++i)
        {
            DataFrame frame = frames.get(i - 1);
            Assert.assertTrue(frame.getStreamId() != 0);
            Assert.assertEquals(i == frames.size(), frame.isEnd());
            aggregate.put(frame.getData());
        }
        aggregate.flip();
        Assert.assertEquals(content, aggregate);
    }

    private List<DataFrame> testGenerateParse(int paddingLength, ByteBuffer... data)
    {
        Generator generator = new Generator(byteBufferPool);

        // Iterate a few times to be sure generator and parser are properly reset.
        final List<DataFrame> frames = new ArrayList<>();
        for (int i = 0; i < 2; ++i)
        {
            Generator.Result result = new Generator.Result(byteBufferPool);
            for (int j = 1; j <= data.length; ++j)
            {
                result = result.merge(generator.generateData(13, paddingLength, data[j - 1].slice(), j == data.length, false));
            }

            Parser parser = new Parser(new Parser.Listener.Adapter()
            {
                @Override
                public boolean onData(DataFrame frame)
                {
                    frames.add(frame);
                    return false;
                }
            });

            frames.clear();
            for (ByteBuffer buffer : result.getByteBuffers())
            {
                parser.parse(buffer);
            }
        }

        return frames;
    }

    @Test
    public void testGenerateParseOneByteAtATime()
    {
        Generator generator = new Generator(byteBufferPool);

        Generator.Result result = generator.generateData(13, 1024, ByteBuffer.wrap(largeContent).slice(), true, false);

        final List<DataFrame> frames = new ArrayList<>();
        Parser parser = new Parser(new Parser.Listener.Adapter()
        {
            @Override
            public boolean onData(DataFrame frame)
            {
                frames.add(frame);
                return false;
            }
        });

        for (ByteBuffer buffer : result.getByteBuffers())
        {
            while (buffer.hasRemaining())
            {
                parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
            }
        }

        Assert.assertEquals(largeContent.length, frames.size());
    }
}
