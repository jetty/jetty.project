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

package org.eclipse.jetty.fcgi.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.ServerGenerator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientParserTest
{
    @Test
    public void testParseResponseHeaders()
    {
        int id = 13;
        HttpFields.Mutable fields = HttpFields.build();

        int statusCode = 200;
        String statusMessage = "OK";
        String contentTypeName = "Content-Type";
        String contentTypeValue = "text/html;charset=utf-8";
        fields.put(contentTypeName, contentTypeValue);

        WritableBufferPool bufferPool = WritableBufferPool.wrap(new ArrayByteBufferPool());
        ServerGenerator generator = new ServerGenerator(bufferPool);
        List<ReadableBuffer> accumulator = new ArrayList<>();
        generator.generateResponseHeaders(accumulator, id, statusCode, statusMessage, fields);

        // Use the fundamental theorem of arithmetic to test the results.
        // This way we know onHeader() has been called the right number of
        // times with the right arguments, and so onHeaders().
        int[] primes = new int[]{2, 3, 5};
        int value = 1;
        for (int prime : primes)
        {
            value *= prime;
        }

        AtomicInteger params = new AtomicInteger(1);
        ClientParser parser = new ClientParser(new ClientParser.Listener()
        {
            @Override
            public void onBegin(int request, int code, String reason)
            {
                assertEquals(statusCode, code);
                assertEquals(statusMessage, reason);
                params.set(params.get() * primes[0]);
            }

            @Override
            public void onHeader(int request, HttpField field)
            {
                assertEquals(id, request);
                if (field.getName().equals(contentTypeName))
                {
                    assertEquals(contentTypeValue, field.getValue().toLowerCase(Locale.ENGLISH));
                    params.set(params.get() * primes[1]);
                }
            }

            @Override
            public boolean onHeaders(int request)
            {
                assertEquals(id, request);
                params.set(params.get() * primes[2]);
                return false;
            }
        });

        for (ReadableBuffer buffer : accumulator)
        {
            parser.parse(buffer);
            assertFalse(buffer.remaining() > 0);
        }

        assertEquals(value, params.get());

        accumulator.forEach(ReadableBuffer::release);
    }

    @Test
    public void testParseNoResponseContent()
    {
        int id = 13;
        HttpFields fields = HttpFields.build()
            .put("Content-Length", "0");

        WritableBufferPool bufferPool = WritableBufferPool.wrap(new ArrayByteBufferPool());
        ServerGenerator generator = new ServerGenerator(bufferPool);
        List<ReadableBuffer> accumulator = new ArrayList<>();
        generator.generateResponseHeaders(accumulator, id, 200, "OK", fields);
        generator.generateResponseContent(accumulator, id, null, true, false);

        AtomicInteger verifier = new AtomicInteger();
        ClientParser parser = new ClientParser(new ClientParser.Listener()
        {
            @Override
            public boolean onContent(int request, FCGI.StreamType stream, ReadableBuffer buffer)
            {
                assertEquals(id, request);
                verifier.addAndGet(2);
                return false;
            }

            @Override
            public boolean onEnd(int request)
            {
                assertEquals(id, request);
                verifier.addAndGet(3);
                return false;
            }
        });

        for (ReadableBuffer buffer : accumulator)
        {
            parser.parse(buffer);
            assertFalse(buffer.remaining() > 0);
        }

        assertEquals(3, verifier.get());

        accumulator.forEach(ReadableBuffer::release);
    }

    @Test
    public void testParseSmallResponseContent()
    {
        int id = 13;
        HttpFields.Mutable fields = HttpFields.build();

        ReadableBuffer content = ReadableBuffer.allocate(1024, false);
        long contentLength = content.remaining();

        int code = 200;
        String contentTypeName = "Content-Length";
        String contentTypeValue = String.valueOf(contentLength);
        fields.put(contentTypeName, contentTypeValue);

        WritableBufferPool bufferPool = WritableBufferPool.wrap(new ArrayByteBufferPool());
        ServerGenerator generator = new ServerGenerator(bufferPool);
        List<ReadableBuffer> accumulator = new ArrayList<>();
        generator.generateResponseHeaders(accumulator, id, code, "OK", fields);
        generator.generateResponseContent(accumulator, id, content, true, false);

        AtomicInteger verifier = new AtomicInteger();
        ClientParser parser = new ClientParser(new ClientParser.Listener()
        {
            @Override
            public boolean onContent(int request, FCGI.StreamType stream, ReadableBuffer buffer)
            {
                assertEquals(id, request);
                assertEquals(contentLength, buffer.remaining());
                verifier.addAndGet(2);
                return false;
            }

            @Override
            public boolean onEnd(int request)
            {
                assertEquals(id, request);
                verifier.addAndGet(3);
                return false;
            }
        });

        for (ReadableBuffer buffer : accumulator)
        {
            parser.parse(buffer);
            assertFalse(buffer.remaining() > 0);
        }

        assertEquals(5, verifier.get());

        accumulator.forEach(ReadableBuffer::release);
    }

    @Test
    public void testParseLargeResponseContent()
    {
        int id = 13;
        HttpFields.Mutable fields = HttpFields.build();

        ReadableBuffer content = ReadableBuffer.allocate(128 * 1024, false);
        long contentLength = content.remaining();

        int code = 200;
        String contentTypeName = "Content-Length";
        String contentTypeValue = String.valueOf(contentLength);
        fields.put(contentTypeName, contentTypeValue);

        WritableBufferPool bufferPool = WritableBufferPool.wrap(new ArrayByteBufferPool());
        ServerGenerator generator = new ServerGenerator(bufferPool);
        List<ReadableBuffer> accumulator = new ArrayList<>();
        generator.generateResponseHeaders(accumulator, id, code, "OK", fields);
        generator.generateResponseContent(accumulator, id, content, true, false);

        AtomicLong totalLength = new AtomicLong();
        AtomicBoolean verifier = new AtomicBoolean();
        ClientParser parser = new ClientParser(new ClientParser.Listener()
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
                verifier.set(true);
                return false;
            }
        });

        for (ReadableBuffer buffer : accumulator)
        {
            parser.parse(buffer);
            assertFalse(buffer.remaining() > 0);
        }

        assertTrue(verifier.get());

        accumulator.forEach(ReadableBuffer::release);
    }

    @ParameterizedTest
    // Frame type 0x01 is BEGIN_REQUEST, cannot be received by clients.
    // Frame type 0x7F is unknown to FCGI.
    @ValueSource(ints = {0x01, 0x7F})
    public void testClientUnknownFrameType(int frameType) throws Exception
    {
        CountDownLatch failureLatch = new CountDownLatch(1);
        ClientParser parser = new ClientParser(new ClientParser.Listener()
        {
            @Override
            public void onFailure(int request, Throwable failure)
            {
                failureLatch.countDown();
            }
        });

        // See Parser for the FCGI record structure.
        WritableBuffer buffer = WritableBuffer.allocate(8, false);
        buffer.put((byte)1);
        buffer.put((byte)frameType);
        buffer.putShort((short)13);
        buffer.putShort((short)0);
        buffer.put((byte)0);
        buffer.put((byte)0);
        ReadableBuffer readable = buffer.toReadable();
        parser.parse(readable);
        assertEquals(0, readable.remaining());
        buffer.release();

        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    // Frame type 0x06 is STDOUT, cannot be received by servers.
    // Frame type 0x7F is unknown to FCGI.
    @ValueSource(ints = {0x06, 0x7F})
    public void testServerUnknownFrameType(int frameType) throws Exception
    {
        CountDownLatch failureLatch = new CountDownLatch(1);
        ServerParser parser = new ServerParser(new ServerParser.Listener()
        {
            @Override
            public void onFailure(int request, Throwable failure)
            {
                failureLatch.countDown();
            }
        });

        // See Parser for the FCGI record structure.
        WritableBuffer buffer = WritableBuffer.allocate(8, false);
        buffer.put((byte)1);
        buffer.put((byte)frameType);
        buffer.putShort((short)13);
        buffer.putShort((short)0);
        buffer.put((byte)0);
        buffer.put((byte)0);
        ReadableBuffer readable = buffer.toReadable();
        parser.parse(readable);
        assertEquals(0, readable.remaining());
        buffer.release();
        
        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    }
}
