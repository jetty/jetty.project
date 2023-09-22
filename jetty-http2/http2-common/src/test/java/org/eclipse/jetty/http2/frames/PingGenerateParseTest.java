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

import org.eclipse.jetty.http2.generator.HeaderGenerator;
import org.eclipse.jetty.http2.generator.PingGenerator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PingGenerateParseTest
{
    private final ByteBufferPool byteBufferPool = new MappedByteBufferPool();

    @Test
    public void testGenerateParse() throws Exception
    {
        PingGenerator generator = new PingGenerator(new HeaderGenerator());

        final List<PingFrame> frames = new ArrayList<>();
        Parser parser = new Parser(byteBufferPool, 4096);
        parser.init(new Parser.Listener.Adapter()
        {
            @Override
            public void onPing(PingFrame frame)
            {
                frames.add(frame);
            }
        });

        byte[] payload = new byte[8];
        new Random().nextBytes(payload);

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.generatePing(lease, payload, true);

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
        PingFrame frame = frames.get(0);
        assertArrayEquals(payload, frame.getPayload());
        assertTrue(frame.isReply());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        PingGenerator generator = new PingGenerator(new HeaderGenerator());

        final List<PingFrame> frames = new ArrayList<>();
        Parser parser = new Parser(byteBufferPool, 4096);
        parser.init(new Parser.Listener.Adapter()
        {
            @Override
            public void onPing(PingFrame frame)
            {
                frames.add(frame);
            }
        });

        byte[] payload = new byte[8];
        new Random().nextBytes(payload);

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.generatePing(lease, payload, true);

            frames.clear();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
                }
            }

            assertEquals(1, frames.size());
            PingFrame frame = frames.get(0);
            assertArrayEquals(payload, frame.getPayload());
            assertTrue(frame.isReply());
        }
    }

    @Test
    public void testPayloadAsLong() throws Exception
    {
        PingGenerator generator = new PingGenerator(new HeaderGenerator());

        final List<PingFrame> frames = new ArrayList<>();
        Parser parser = new Parser(byteBufferPool, 4096);
        parser.init(new Parser.Listener.Adapter()
        {
            @Override
            public void onPing(PingFrame frame)
            {
                frames.add(frame);
            }
        });

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        PingFrame ping = new PingFrame(System.nanoTime(), true);
        generator.generate(lease, ping);

        for (ByteBuffer buffer : lease.getByteBuffers())
        {
            while (buffer.hasRemaining())
            {
                parser.parse(buffer);
            }
        }

        assertEquals(1, frames.size());
        PingFrame pong = frames.get(0);
        assertEquals(ping.getPayloadAsLong(), pong.getPayloadAsLong());
        assertTrue(pong.isReply());
    }
}
