//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.fcgi.parser;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.fcgi.generator.ServerGenerator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.junit.Assert;
import org.junit.Test;

public class ClientParserTest
{
    @Test
    public void testParseResponseHeaders() throws Exception
    {
        final int id = 13;
        HttpFields fields = new HttpFields();

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
            value *= prime;

        final AtomicInteger params = new AtomicInteger(1);
        ClientParser parser = new ClientParser(new ClientParser.Listener.Adapter()
        {
            @Override
            public void onBegin(int request, int code, String reason)
            {
                Assert.assertEquals(statusCode, code);
                Assert.assertEquals(statusMessage, reason);
                params.set(params.get() * primes[0]);
            }

            @Override
            public void onHeader(int request, HttpField field)
            {
                Assert.assertEquals(id, request);
                switch (field.getName())
                {
                    case contentTypeName:
                        Assert.assertEquals(contentTypeValue, field.getValue());
                        params.set(params.get() * primes[1]);
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onHeaders(int request)
            {
                Assert.assertEquals(id, request);
                params.set(params.get() * primes[2]);
            }
        });

        for (ByteBuffer buffer : result.getByteBuffers())
        {
            parser.parse(buffer);
            Assert.assertFalse(buffer.hasRemaining());
        }

        Assert.assertEquals(value, params.get());
    }

    @Test
    public void testParseNoResponseContent() throws Exception
    {
        final int id = 13;
        HttpFields fields = new HttpFields();
        fields.put("Content-Length", "0");

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
                Assert.assertEquals(id, request);
                verifier.addAndGet(2);
                return false;
            }

            @Override
            public void onEnd(int request)
            {
                Assert.assertEquals(id, request);
                verifier.addAndGet(3);
            }
        });

        for (ByteBuffer buffer : result1.getByteBuffers())
        {
            parser.parse(buffer);
            Assert.assertFalse(buffer.hasRemaining());
        }
        for (ByteBuffer buffer : result2.getByteBuffers())
        {
            parser.parse(buffer);
            Assert.assertFalse(buffer.hasRemaining());
        }

        Assert.assertEquals(3, verifier.get());
    }

    @Test
    public void testParseSmallResponseContent() throws Exception
    {
        final int id = 13;
        HttpFields fields = new HttpFields();

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
                Assert.assertEquals(id, request);
                Assert.assertEquals(contentLength, buffer.remaining());
                verifier.addAndGet(2);
                return false;
            }

            @Override
            public void onEnd(int request)
            {
                Assert.assertEquals(id, request);
                verifier.addAndGet(3);
            }
        });

        for (ByteBuffer buffer : result1.getByteBuffers())
        {
            parser.parse(buffer);
            Assert.assertFalse(buffer.hasRemaining());
        }
        for (ByteBuffer buffer : result2.getByteBuffers())
        {
            parser.parse(buffer);
            Assert.assertFalse(buffer.hasRemaining());
        }

        Assert.assertEquals(5, verifier.get());
    }

    @Test
    public void testParseLargeResponseContent() throws Exception
    {
        final int id = 13;
        HttpFields fields = new HttpFields();

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
                Assert.assertEquals(id, request);
                totalLength.addAndGet(buffer.remaining());
                return false;
            }

            @Override
            public void onEnd(int request)
            {
                Assert.assertEquals(id, request);
                Assert.assertEquals(contentLength, totalLength.get());
                verifier.set(true);
            }
        });

        for (ByteBuffer buffer : result1.getByteBuffers())
        {
            parser.parse(buffer);
            Assert.assertFalse(buffer.hasRemaining());
        }
        for (ByteBuffer buffer : result2.getByteBuffers())
        {
            parser.parse(buffer);
            Assert.assertFalse(buffer.hasRemaining());
        }

        Assert.assertTrue(verifier.get());
    }
}
