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
import org.eclipse.jetty.http2.generator.PriorityGenerator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PriorityGenerateParseTest
{
    private final ByteBufferPool byteBufferPool = new MappedByteBufferPool();

    @Test
    public void testGenerateParse() throws Exception
    {
        PriorityGenerator generator = new PriorityGenerator(new HeaderGenerator());

        final List<PriorityFrame> frames = new ArrayList<>();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onPriority(PriorityFrame frame)
            {
                frames.add(frame);
            }
        }, 4096, 8192);
        parser.init(UnaryOperator.identity());

        int streamId = 13;
        int parentStreamId = 17;
        int weight = 256;
        boolean exclusive = true;

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.generatePriority(lease, streamId, parentStreamId, weight, exclusive);

            frames.clear();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(buffer);
                }
            }
        }

        assertEquals(1, frames.size());
        PriorityFrame frame = frames.get(0);
        assertEquals(streamId, frame.getStreamId());
        assertEquals(parentStreamId, frame.getParentStreamId());
        assertEquals(weight, frame.getWeight());
        assertEquals(exclusive, frame.isExclusive());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        PriorityGenerator generator = new PriorityGenerator(new HeaderGenerator());

        final List<PriorityFrame> frames = new ArrayList<>();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onPriority(PriorityFrame frame)
            {
                frames.add(frame);
            }
        }, 4096, 8192);
        parser.init(UnaryOperator.identity());

        int streamId = 13;
        int parentStreamId = 17;
        int weight = 3;
        boolean exclusive = true;

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.generatePriority(lease, streamId, parentStreamId, weight, exclusive);

            frames.clear();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
                }
            }

            assertEquals(1, frames.size());
            PriorityFrame frame = frames.get(0);
            assertEquals(streamId, frame.getStreamId());
            assertEquals(parentStreamId, frame.getParentStreamId());
            assertEquals(weight, frame.getWeight());
            assertEquals(exclusive, frame.isExclusive());
        }
    }
}
