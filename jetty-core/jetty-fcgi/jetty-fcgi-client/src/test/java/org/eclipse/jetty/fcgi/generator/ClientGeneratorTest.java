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

package org.eclipse.jetty.fcgi.generator;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.parser.ServerParser;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ArrayRetainableByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class ClientGeneratorTest
{
    @Test
    public void testGenerateRequestHeaders()
    {
        HttpFields.Mutable fields = HttpFields.build();

        // Short name, short value
        final String shortShortName = "REQUEST_METHOD";
        String shortShortValue = "GET";
        fields.put(new HttpField(shortShortName, shortShortValue));

        // Short name, long value
        final String shortLongName = "REQUEST_URI";
        // Be sure it's longer than 127 chars to test the large value
        String shortLongValue = "/api/0.6/map?bbox=-64.217736,-31.456810,-64.187736,-31.432322,filler=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        fields.put(new HttpField(shortLongName, shortLongValue));

        // Long name, short value
        // Be sure it's longer than 127 chars to test the large name
        final String longShortName = "FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210";
        String longShortValue = "api.openstreetmap.org";
        fields.put(new HttpField(longShortName, longShortValue));

        // Long name, long value
        char[] chars = new char[ClientGenerator.MAX_PARAM_LENGTH];
        Arrays.fill(chars, 'z');
        String longLongName = new String(chars);
        String longLongValue = new String(chars);
        fields.put(new HttpField(longLongName, longLongValue));

        RetainableByteBufferPool bufferPool = new ArrayRetainableByteBufferPool();
        ClientGenerator generator = new ClientGenerator(bufferPool);
        RetainableByteBufferPool.Accumulator accumulator = new RetainableByteBufferPool.Accumulator();
        int id = 13;
        generator.generateRequestHeaders(accumulator, id, fields);

        // Use the fundamental theorem of arithmetic to test the results.
        // This way we know onHeader() has been called the right number of
        // times with the right arguments, and so onHeaders().
        int[] primes = new int[]{2, 3, 5, 7, 11};
        int value = 1;
        for (int prime : primes)
        {
            value *= prime;
        }

        AtomicInteger params = new AtomicInteger(1);
        ServerParser parser = new ServerParser(new ServerParser.Listener.Adapter()
        {
            @Override
            public void onHeader(int request, HttpField field)
            {
                assertEquals(id, request);
                switch (field.getName())
                {
                    case shortShortName ->
                    {
                        assertEquals(shortShortValue, field.getValue());
                        params.set(params.get() * primes[0]);
                    }
                    case shortLongName ->
                    {
                        assertEquals(shortLongValue, field.getValue());
                        params.set(params.get() * primes[1]);
                    }
                    case longShortName ->
                    {
                        assertEquals(longShortValue, field.getValue());
                        params.set(params.get() * primes[2]);
                    }
                    default ->
                    {
                        assertEquals(longLongName, field.getName());
                        assertEquals(longLongValue, field.getValue());
                        params.set(params.get() * primes[3]);
                    }
                }
            }

            @Override
            public boolean onHeaders(int request)
            {
                assertEquals(id, request);
                params.set(params.get() * primes[4]);
                return false;
            }
        });

        for (ByteBuffer buffer : accumulator.getByteBuffers())
        {
            parser.parse(buffer);
            assertFalse(buffer.hasRemaining());
        }

        assertEquals(value, params.get());

        // Parse again byte by byte
        params.set(1);
        for (ByteBuffer buffer : accumulator.getByteBuffers())
        {
            buffer.flip();
            while (buffer.hasRemaining())
            {
                parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
            }
        }

        assertEquals(value, params.get());

        accumulator.release();
    }

    @Test
    public void testGenerateSmallRequestContent() throws Exception
    {
        testGenerateRequestContent(1024);
    }

    @Test
    public void testGenerateLargeRequestContent() throws Exception
    {
        testGenerateRequestContent(128 * 1024);
    }

    private void testGenerateRequestContent(int contentLength) throws Exception
    {
        ByteBuffer content = ByteBuffer.allocate(contentLength);

        RetainableByteBufferPool bufferPool = new ArrayRetainableByteBufferPool();
        ClientGenerator generator = new ClientGenerator(bufferPool);
        RetainableByteBufferPool.Accumulator accumulator = new RetainableByteBufferPool.Accumulator();
        int id = 13;
        generator.generateRequestContent(accumulator, id, content, true);

        AtomicInteger totalLength = new AtomicInteger();
        ServerParser parser = new ServerParser(new ServerParser.Listener.Adapter()
        {
            @Override
            public boolean onContent(int request, FCGI.StreamType stream, ByteBuffer buffer)
            {
                assertEquals(id, request);
                totalLength.addAndGet(buffer.remaining());
                return false;
            }

            @Override
            public void onEnd(int request)
            {
                assertEquals(id, request);
                assertEquals(contentLength, totalLength.get());
            }
        });

        for (ByteBuffer buffer : accumulator.getByteBuffers())
        {
            parser.parse(buffer);
            assertFalse(buffer.hasRemaining());
        }

        // Parse again one byte at a time
        for (ByteBuffer buffer : accumulator.getByteBuffers())
        {
            buffer.flip();
            while (buffer.hasRemaining())
            {
                parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
            }
        }

        accumulator.release();
    }
}
