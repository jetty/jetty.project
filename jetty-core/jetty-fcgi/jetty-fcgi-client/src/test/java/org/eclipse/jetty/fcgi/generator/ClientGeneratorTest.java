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

package org.eclipse.jetty.fcgi.generator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.parser.ServerParser;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        WritableBufferPool bufferPool = WritableBufferPool.wrap(new ArrayByteBufferPool());
        ClientGenerator generator = new ClientGenerator(bufferPool);
        List<ReadableBuffer> accumulator = new ArrayList<>();
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
        ServerParser parser = new ServerParser(new ServerParser.Listener()
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

        ReadableBuffer buffer = ReadableBuffer.accumulate(accumulator);
        accumulator.forEach(ReadableBuffer::release);

        parser.parse(buffer);
        assertEquals(0, buffer.remaining());

        assertEquals(value, params.get());

        // Parse again byte by byte.
        params.set(1);
        buffer.position(0);
        while (buffer.remaining() > 0)
        {
            ReadableBuffer slice = buffer.slice(buffer.position(), 1);
            buffer.position(buffer.position() + 1);
            parser.parse(slice);
            slice.release();
        }

        assertEquals(value, params.get());

        buffer.release();
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
        ReadableBuffer content = ReadableBuffer.allocate(contentLength, false);

        WritableBufferPool bufferPool = WritableBufferPool.wrap(new ArrayByteBufferPool());
        ClientGenerator generator = new ClientGenerator(bufferPool);
        List<ReadableBuffer> accumulator = new ArrayList<>();
        int id = 13;
        generator.generateRequestContent(accumulator, id, content, true);

        AtomicLong totalLength = new AtomicLong();
        ServerParser parser = new ServerParser(new ServerParser.Listener()
        {
            @Override
            public boolean onContent(int request, FCGI.StreamType stream, ReadableBuffer buffer)
            {
                assertEquals(id, request);
                totalLength.addAndGet(buffer.remaining());
                return false;
            }

            @Override
            public boolean onEnd(int request)
            {
                assertEquals(id, request);
                assertEquals(contentLength, totalLength.get());
                return false;
            }
        });

        ReadableBuffer buffer = ReadableBuffer.accumulate(accumulator);
        accumulator.forEach(ReadableBuffer::release);

        parser.parse(buffer);
        assertEquals(0, buffer.remaining());

        // Parse again one byte at a time.
        buffer.position(0);
        while (buffer.remaining() > 0)
        {
            ReadableBuffer slice = buffer.slice(buffer.position(), 1);
            buffer.position(buffer.position() + 1);
            parser.parse(slice);
            slice.release();
        }

        buffer.release();
        content.release();
    }
}
