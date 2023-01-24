//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.frames;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http2.internal.generator.DataGenerator;
import org.eclipse.jetty.http2.internal.generator.HeaderGenerator;
import org.eclipse.jetty.http2.internal.parser.Parser;
import org.eclipse.jetty.io.ArrayRetainableByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DataGenerateParseTest
{
    private final byte[] smallContent = new byte[128];
    private final byte[] largeContent = new byte[128 * 1024];
    private final RetainableByteBufferPool bufferPool = new ArrayRetainableByteBufferPool();

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
        assertEquals(1, frames.size());
        DataFrame frame = frames.get(0);
        assertTrue(frame.getStreamId() != 0);
        assertTrue(frame.isEndStream());
        assertEquals(content, frame.getByteBuffer());
    }

    @Test
    public void testGenerateParseLargeContent()
    {
        ByteBuffer content = ByteBuffer.wrap(largeContent);
        List<DataFrame> frames = testGenerateParse(content);
        assertEquals(8, frames.size());
        ByteBuffer aggregate = ByteBuffer.allocate(content.remaining());
        for (int i = 1; i <= frames.size(); ++i)
        {
            DataFrame frame = frames.get(i - 1);
            assertTrue(frame.getStreamId() != 0);
            assertEquals(i == frames.size(), frame.isEndStream());
            aggregate.put(frame.getByteBuffer());
        }
        aggregate.flip();
        assertEquals(content, aggregate);
    }

    private List<DataFrame> testGenerateParse(ByteBuffer data)
    {
        DataGenerator generator = new DataGenerator(new HeaderGenerator(bufferPool));

        final List<DataFrame> frames = new ArrayList<>();
        Parser parser = new Parser(bufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onData(DataFrame frame)
            {
                frames.add(frame);
            }
        }, 4096, 8192);
        parser.init(UnaryOperator.identity());

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            RetainableByteBufferPool.Accumulator accumulator = new RetainableByteBufferPool.Accumulator();
            ByteBuffer slice = data.slice();
            int generated = 0;
            while (true)
            {
                generated += generator.generateData(accumulator, 13, slice, true, slice.remaining());
                generated -= Frame.HEADER_LENGTH;
                if (generated == data.remaining())
                    break;
            }

            frames.clear();
            for (ByteBuffer buffer : accumulator.getByteBuffers())
            {
                parser.parse(buffer);
            }
        }

        return frames;
    }

    @Test
    public void testGenerateParseOneByteAtATime()
    {
        DataGenerator generator = new DataGenerator(new HeaderGenerator(bufferPool));

        final List<DataFrame> frames = new ArrayList<>();
        Parser parser = new Parser(bufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onData(DataFrame frame)
            {
                frames.add(frame);
            }
        }, 4096, 8192);
        parser.init(UnaryOperator.identity());

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            RetainableByteBufferPool.Accumulator accumulator = new RetainableByteBufferPool.Accumulator();
            ByteBuffer data = ByteBuffer.wrap(largeContent);
            ByteBuffer slice = data.slice();
            int generated = 0;
            while (true)
            {
                generated += generator.generateData(accumulator, 13, slice, true, slice.remaining());
                generated -= Frame.HEADER_LENGTH;
                if (generated == data.remaining())
                    break;
            }

            frames.clear();
            for (ByteBuffer buffer : accumulator.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
                }
            }

            assertEquals(largeContent.length, frames.size());
        }
    }
}
