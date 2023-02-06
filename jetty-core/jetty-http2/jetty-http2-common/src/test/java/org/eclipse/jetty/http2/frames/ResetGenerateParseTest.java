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
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http2.generator.HeaderGenerator;
import org.eclipse.jetty.http2.generator.ResetGenerator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ArrayRetainableByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResetGenerateParseTest
{
    private final RetainableByteBufferPool bufferPool = new ArrayRetainableByteBufferPool();

    @Test
    public void testGenerateParse() throws Exception
    {
        ResetGenerator generator = new ResetGenerator(new HeaderGenerator(bufferPool));

        final List<ResetFrame> frames = new ArrayList<>();
        Parser parser = new Parser(bufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onReset(ResetFrame frame)
            {
                frames.add(frame);
            }
        }, 4096, 8192);
        parser.init(UnaryOperator.identity());

        int streamId = 13;
        int error = 17;

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            RetainableByteBufferPool.Accumulator accumulator = new RetainableByteBufferPool.Accumulator();
            generator.generateReset(accumulator, streamId, error);

            frames.clear();
            for (ByteBuffer buffer : accumulator.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(buffer);
                }
            }
        }

        assertEquals(1, frames.size());
        ResetFrame frame = frames.get(0);
        assertEquals(streamId, frame.getStreamId());
        assertEquals(error, frame.getError());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        ResetGenerator generator = new ResetGenerator(new HeaderGenerator(bufferPool));

        final List<ResetFrame> frames = new ArrayList<>();
        Parser parser = new Parser(bufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onReset(ResetFrame frame)
            {
                frames.add(frame);
            }
        }, 4096, 8192);
        parser.init(UnaryOperator.identity());

        int streamId = 13;
        int error = 17;

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            RetainableByteBufferPool.Accumulator accumulator = new RetainableByteBufferPool.Accumulator();
            generator.generateReset(accumulator, streamId, error);

            frames.clear();
            for (ByteBuffer buffer : accumulator.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
                }
            }

            assertEquals(1, frames.size());
            ResetFrame frame = frames.get(0);
            assertEquals(streamId, frame.getStreamId());
            assertEquals(error, frame.getError());
        }
    }
}
