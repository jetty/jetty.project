//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.fcgi.generator.ServerGenerator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientParserTest
{
    @Test
    public void testParseResponseHeaders() throws Exception
    {
        final int id = 13;
        HttpFields.Mutable fields = HttpFields.build();

        final int statusCode = 200;
        final String statusMessage = "OK";
        final String contentTypeName = "Content-Type";
        final String contentTypeValue = "text/html;charset=utf-8";
        fields.put(contentTypeName, contentTypeValue);

        ByteBufferPool byteBufferPool = new MappedByteBufferPool();
        ServerGenerator generator = new ServerGenerator(byteBufferPool);
        Generator.Result result = generator.generateResponseHeaders(id, statusCode, statusMessage, fields, null);

        // Use the fundamental theorem of arithmetic to test the results.
        // This way we know onHeader() has been called the right number of
        // times with the right arguments, and so onHeaders().
        final int[] primes = new int[]{2, 3, 5};
        int value = 1;
        for (int prime : primes)
        {
            value *= prime;
        }

        final AtomicInteger params = new AtomicInteger(1);
        ClientParser parser = new ClientParser(new ClientParser.Listener.Adapter()
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
                switch (field.getName())
                {
                    case contentTypeName:
                        assertEquals(contentTypeValue, field.getValue().toLowerCase(Locale.ENGLISH));
                        params.set(params.get() * primes[1]);
                        break;
                    default:
                        break;
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

        for (ByteBuffer buffer : result.getByteBuffers())
        {
            parser.parse(buffer);
            assertFalse(buffer.hasRemaining());
        }

        assertEquals(value, params.get());
    }

    @Test
    public void testParseNoResponseContent() throws Exception
    {
        final int id = 13;
        HttpFields fields = HttpFields.build()
            .put("Content-Length", "0");

        ByteBufferPool byteBufferPool = new MappedByteBufferPool();
        ServerGenerator generator = new ServerGenerator(byteBufferPool);
        Generator.Result result1 = generator.generateResponseHeaders(id, 200, "OK", fields, null);
        Generator.Result result2 = generator.generateResponseContent(id, null, true, false, null);

        final AtomicInteger verifier = new AtomicInteger();
        ClientParser parser = new ClientParser(new ClientParser.Listener.Adapter()
        {
            @Override
            public boolean onContent(int request, FCGI.StreamType stream, ByteBuffer buffer)
            {
                assertEquals(id, request);
                verifier.addAndGet(2);
                return false;
            }

            @Override
            public void onEnd(int request)
            {
                assertEquals(id, request);
                verifier.addAndGet(3);
            }
        });

        for (ByteBuffer buffer : result1.getByteBuffers())
        {
            parser.parse(buffer);
            assertFalse(buffer.hasRemaining());
        }
        for (ByteBuffer buffer : result2.getByteBuffers())
        {
            parser.parse(buffer);
            assertFalse(buffer.hasRemaining());
        }

        assertEquals(3, verifier.get());
    }

    @Test
    public void testParseSmallResponseContent() throws Exception
    {
        final int id = 13;
        HttpFields.Mutable fields = HttpFields.build();

        ByteBuffer content = ByteBuffer.wrap(new byte[1024]);
        final int contentLength = content.remaining();

        final int code = 200;
        final String contentTypeName = "Content-Length";
        final String contentTypeValue = String.valueOf(contentLength);
        fields.put(contentTypeName, contentTypeValue);

        ByteBufferPool byteBufferPool = new MappedByteBufferPool();
        ServerGenerator generator = new ServerGenerator(byteBufferPool);
        Generator.Result result1 = generator.generateResponseHeaders(id, code, "OK", fields, null);
        Generator.Result result2 = generator.generateResponseContent(id, content, true, false, null);

        final AtomicInteger verifier = new AtomicInteger();
        ClientParser parser = new ClientParser(new ClientParser.Listener.Adapter()
        {
            @Override
            public boolean onContent(int request, FCGI.StreamType stream, ByteBuffer buffer)
            {
                assertEquals(id, request);
                assertEquals(contentLength, buffer.remaining());
                verifier.addAndGet(2);
                return false;
            }

            @Override
            public void onEnd(int request)
            {
                assertEquals(id, request);
                verifier.addAndGet(3);
            }
        });

        for (ByteBuffer buffer : result1.getByteBuffers())
        {
            parser.parse(buffer);
            assertFalse(buffer.hasRemaining());
        }
        for (ByteBuffer buffer : result2.getByteBuffers())
        {
            parser.parse(buffer);
            assertFalse(buffer.hasRemaining());
        }

        assertEquals(5, verifier.get());
    }

    @Test
    public void testParseLargeResponseContent() throws Exception
    {
        final int id = 13;
        HttpFields.Mutable fields = HttpFields.build();

        ByteBuffer content = ByteBuffer.wrap(new byte[128 * 1024]);
        final int contentLength = content.remaining();

        final int code = 200;
        final String contentTypeName = "Content-Length";
        final String contentTypeValue = String.valueOf(contentLength);
        fields.put(contentTypeName, contentTypeValue);

        ByteBufferPool byteBufferPool = new MappedByteBufferPool();
        ServerGenerator generator = new ServerGenerator(byteBufferPool);
        Generator.Result result1 = generator.generateResponseHeaders(id, code, "OK", fields, null);
        Generator.Result result2 = generator.generateResponseContent(id, content, true, false, null);

        final AtomicInteger totalLength = new AtomicInteger();
        final AtomicBoolean verifier = new AtomicBoolean();
        ClientParser parser = new ClientParser(new ClientParser.Listener.Adapter()
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
                verifier.set(true);
            }
        });

        for (ByteBuffer buffer : result1.getByteBuffers())
        {
            parser.parse(buffer);
            assertFalse(buffer.hasRemaining());
        }
        for (ByteBuffer buffer : result2.getByteBuffers())
        {
            parser.parse(buffer);
            assertFalse(buffer.hasRemaining());
        }

        assertTrue(verifier.get());
    }
}
