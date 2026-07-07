//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.eclipse.jetty.http2.generator.DataGenerator;
import org.eclipse.jetty.http2.generator.HeaderGenerator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DataGenerateParseTest
{
    private final byte[] smallContent = new byte[128];
    private final byte[] largeContent = new byte[128 * 1024];
    private final WritableBufferPool bufferPool = WritableBufferPool.wrap(new ArrayByteBufferPool());

    public DataGenerateParseTest()
    {
        Random random = new Random();
        random.nextBytes(smallContent);
        random.nextBytes(largeContent);
    }

    @Test
    public void testGenerateParseNoContentNoPadding()
    {
        testGenerateParseContent(ReadableBuffer.EMPTY);
    }

    @Test
    public void testGenerateParseSmallContentNoPadding()
    {
        testGenerateParseContent(ReadableBuffer.wrap(smallContent));
    }

    private void testGenerateParseContent(ReadableBuffer content)
    {
        List<DataFrame> frames = testGenerateParse(content);
        assertEquals(1, frames.size());
        DataFrame frame = frames.get(0);
        assertTrue(frame.getStreamId() != 0);
        assertTrue(frame.isEndStream());
        assertThat(BufferUtil.toArray(frame.acquire()), is(BufferUtil.toArray(content)));
        frames.forEach(DataFrame::release);
    }

    @Test
    public void testGenerateParseLargeContent()
    {
        ReadableBuffer content = ReadableBuffer.wrap(largeContent);
        List<DataFrame> frames = testGenerateParse(content);
        assertEquals(8, frames.size());
        WritableBuffer aggregate = WritableBuffer.allocate((int)content.remaining(), false);
        for (int i = 1; i <= frames.size(); ++i)
        {
            DataFrame frame = frames.get(i - 1);
            assertTrue(frame.getStreamId() != 0);
            assertEquals(i == frames.size(), frame.isEndStream());
            ReadableBuffer rb = frame.acquire();
            BufferUtil.put(rb, aggregate);
            rb.release();
        }
        assertThat(BufferUtil.toArray(aggregate.toReadable()), is(BufferUtil.toArray(content)));
        frames.forEach(DataFrame::release);
    }

    private List<DataFrame> testGenerateParse(ReadableBuffer data)
    {
        DataGenerator generator = new DataGenerator(new HeaderGenerator(bufferPool));

        final List<DataFrame> frames = new ArrayList<>();
        Parser parser = new Parser(bufferPool, 8192);
        parser.init(new Parser.Listener()
        {
            @Override
            public void onData(DataFrame frame)
            {
                frame.retain();
                frames.add(frame);
            }
        });

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            List<ReadableBuffer> accumulator = new ArrayList<>();
            ReadableBuffer slice = data.slice();
            int generated = 0;
            while (true)
            {
                generated += generator.generateData(accumulator, 13, slice, true, (int)slice.remaining());
                generated -= Frame.HEADER_LENGTH;
                if (generated == data.remaining())
                    break;
            }
            slice.release();

            frames.clear();
            ReadableBuffer rb = ReadableBuffer.accumulate(accumulator);
            accumulator.forEach(ReadableBuffer::release);
            UnknownParseTest.parse(parser, rb);
            rb.release();
        }

        return frames;
    }

    @Test
    public void testGenerateParseOneByteAtATime()
    {
        DataGenerator generator = new DataGenerator(new HeaderGenerator(bufferPool));

        final List<DataFrame> frames = new ArrayList<>();
        Parser parser = new Parser(bufferPool, 8192);
        parser.init(new Parser.Listener()
        {
            @Override
            public void onData(DataFrame frame)
            {
                frame.retain();
                frames.add(frame);
            }
        });

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            List<ReadableBuffer> accumulator = new ArrayList<>();
            ReadableBuffer data = ReadableBuffer.wrap(largeContent);
            ReadableBuffer slice = data.slice();
            int generated = 0;
            while (true)
            {
                generated += generator.generateData(accumulator, 13, slice, true, (int)slice.remaining());
                generated -= Frame.HEADER_LENGTH;
                if (generated == data.remaining())
                    break;
            }
            slice.release();

            ReadableBuffer rb = ReadableBuffer.accumulate(accumulator);
            accumulator.forEach(ReadableBuffer::release);
            UnknownParseTest.parse(parser, rb);
            rb.release();

            assertEquals(largeContent.length, frames.stream().mapToLong(DataFrame::remaining).sum());
            frames.forEach(DataFrame::release);
            frames.clear();
        }
    }
}
