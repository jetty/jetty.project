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
import org.junit.Assert;
import org.junit.Test;

public class PingGenerateParseTest
{
    private final ByteBufferPool byteBufferPool = new MappedByteBufferPool();

    @Test
    public void testGenerateParse() throws Exception
    {
        Generator generator = new Generator(byteBufferPool);

        byte[] payload = new byte[8];
        new Random().nextBytes(payload);

        // Iterate a few times to be sure generator and parser are properly reset.
        final List<PingFrame> frames = new ArrayList<>();
        for (int i = 0; i < 2; ++i)
        {
            ByteBufferPool.Lease lease = generator.generatePing(payload, true);
            Parser parser = new Parser(new Parser.Listener.Adapter()
            {
                @Override
                public boolean onPing(PingFrame frame)
                {
                    frames.add(frame);
                    return false;
                }
            });

            frames.clear();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(buffer);
                }
            }
        }

        Assert.assertEquals(1, frames.size());
        PingFrame frame = frames.get(0);
        Assert.assertArrayEquals(payload, frame.getPayload());
        Assert.assertTrue(frame.isReply());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        Generator generator = new Generator(byteBufferPool);

        byte[] payload = new byte[8];
        new Random().nextBytes(payload);

        final List<PingFrame> frames = new ArrayList<>();
        ByteBufferPool.Lease lease = generator.generatePing(payload, true);
        Parser parser = new Parser(new Parser.Listener.Adapter()
        {
            @Override
            public boolean onPing(PingFrame frame)
            {
                frames.add(frame);
                return false;
            }
        });

        for (ByteBuffer buffer : lease.getByteBuffers())
        {
            while (buffer.hasRemaining())
            {
                parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
            }
        }

        Assert.assertEquals(1, frames.size());
        PingFrame frame = frames.get(0);
        Assert.assertArrayEquals(payload, frame.getPayload());
        Assert.assertTrue(frame.isReply());
    }
}
