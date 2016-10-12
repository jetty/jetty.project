//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http2.generator.DataGenerator;
import org.eclipse.jetty.http2.generator.HeaderGenerator;
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
        testGenerateParseContent(BufferUtil.EMPTY_BUFFER);
    }

    @Test
    public void testGenerateParseSmallContentNoPadding()
    {
        testGenerateParseContent(ByteBuffer.wrap(smallContent));
    }

    private void testGenerateParseContent(ByteBuffer content)
    {
        List<DataFrame> frames = testGenerateParse(content);
        Assert.assertEquals(1, frames.size());
        DataFrame frame = frames.get(0);
        Assert.assertTrue(frame.getStreamId() != 0);
        Assert.assertTrue(frame.isEndStream());
        Assert.assertEquals(content, frame.getData());
    }

    @Test
    public  void testGenerateParseLargeContent()
    {
        ByteBuffer content = ByteBuffer.wrap(largeContent);
        List<DataFrame> frames = testGenerateParse(content);
        Assert.assertEquals(8, frames.size());
        ByteBuffer aggregate = ByteBuffer.allocate(content.remaining());
        for (int i = 1; i <= frames.size(); ++i)
        {
            DataFrame frame = frames.get(i - 1);
            Assert.assertTrue(frame.getStreamId() != 0);
            Assert.assertEquals(i == frames.size(), frame.isEndStream());
            aggregate.put(frame.getData());
        }
        aggregate.flip();
        Assert.assertEquals(content, aggregate);
    }

    private List<DataFrame> testGenerateParse(ByteBuffer data)
    {
        DataGenerator generator = new DataGenerator(new HeaderGenerator());

        final List<DataFrame> frames = new ArrayList<>();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onData(DataFrame frame)
            {
                frames.add(frame);
            }
        }, 4096, 8192);

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            ByteBuffer slice = data.slice();
            int generated = 0;
            while (true)
            {
                generated += generator.generateData(lease, 13, slice, true, slice.remaining());
                generated -= Frame.HEADER_LENGTH;
                if (generated == data.remaining())
                    break;
            }

            frames.clear();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                parser.parse(buffer);
            }
        }

        return frames;
    }

    @Test
    public void testGenerateParseOneByteAtATime()
    {
        DataGenerator generator = new DataGenerator(new HeaderGenerator());

        final List<DataFrame> frames = new ArrayList<>();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onData(DataFrame frame)
            {
                frames.add(frame);
            }
        }, 4096, 8192);

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            ByteBuffer data = ByteBuffer.wrap(largeContent);
            ByteBuffer slice = data.slice();
            int generated = 0;
            while (true)
            {
                generated += generator.generateData(lease, 13, slice, true, slice.remaining());
                generated -= Frame.HEADER_LENGTH;
                if (generated == data.remaining())
                    break;
            }

            frames.clear();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
                }
            }

            Assert.assertEquals(largeContent.length, frames.size());
        }
    }
}
