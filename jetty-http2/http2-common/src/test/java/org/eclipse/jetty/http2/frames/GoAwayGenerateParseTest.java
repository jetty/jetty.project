//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http2.generator.GoAwayGenerator;
import org.eclipse.jetty.http2.generator.HeaderGenerator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class GoAwayGenerateParseTest
{
    private final ByteBufferPool byteBufferPool = new MappedByteBufferPool();

    @Test
    public void testGenerateParse() throws Exception
    {
        GoAwayGenerator generator = new GoAwayGenerator(new HeaderGenerator());

        final List<GoAwayFrame> frames = new ArrayList<>();
        Parser parser = new Parser(byteBufferPool, 4096);
        parser.init(new Parser.Listener.Adapter()
        {
            @Override
            public void onGoAway(GoAwayFrame frame)
            {
                frames.add(frame);
            }
        });

        int lastStreamId = 13;
        int error = 17;

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.generateGoAway(lease, lastStreamId, error, null);

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
        GoAwayFrame frame = frames.get(0);
        assertEquals(lastStreamId, frame.getLastStreamId());
        assertEquals(error, frame.getError());
        assertNull(frame.getPayload());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        GoAwayGenerator generator = new GoAwayGenerator(new HeaderGenerator());

        final List<GoAwayFrame> frames = new ArrayList<>();
        Parser parser = new Parser(byteBufferPool, 4096);
        parser.init(new Parser.Listener.Adapter()
        {
            @Override
            public void onGoAway(GoAwayFrame frame)
            {
                frames.add(frame);
            }
        });

        int lastStreamId = 13;
        int error = 17;
        byte[] payload = new byte[16];
        new Random().nextBytes(payload);

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.generateGoAway(lease, lastStreamId, error, payload);

            frames.clear();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
                }
            }

            assertEquals(1, frames.size());
            GoAwayFrame frame = frames.get(0);
            assertEquals(lastStreamId, frame.getLastStreamId());
            assertEquals(error, frame.getError());
            assertArrayEquals(payload, frame.getPayload());
        }
    }
}
