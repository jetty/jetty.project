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

package org.eclipse.jetty.fcgi.generator;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.parser.ServerParser;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.junit.Assert;
import org.junit.Test;

public class ClientGeneratorTest
{
    @Test
    public void testGenerateRequestHeaders() throws Exception
    {
        HttpFields fields = new HttpFields();

        // Short name, short value
        final String shortShortName = "REQUEST_METHOD";
        final String shortShortValue = "GET";
        fields.put(new HttpField(shortShortName, shortShortValue));

        // Short name, long value
        final String shortLongName = "REQUEST_URI";
        // Be sure it's longer than 127 chars to test the large value
        final String shortLongValue = "/api/0.6/map?bbox=-64.217736,-31.456810,-64.187736,-31.432322,filler=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        fields.put(new HttpField(shortLongName, shortLongValue));

        // Long name, short value
        // Be sure it's longer than 127 chars to test the large name
        final String longShortName = "FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210";
        final String longShortValue = "api.openstreetmap.org";
        fields.put(new HttpField(longShortName, longShortValue));

        // Long name, long value
        char[] chars = new char[ClientGenerator.MAX_PARAM_LENGTH];
        Arrays.fill(chars, 'z');
        final String longLongName = new String(chars);
        final String longLongValue = new String(chars);
        fields.put(new HttpField(longLongName, longLongValue));

        ByteBufferPool byteBufferPool = new MappedByteBufferPool();
        ClientGenerator generator = new ClientGenerator(byteBufferPool);
        final int id = 13;
        Generator.Result result = generator.generateRequestHeaders(id, fields, null);

        // Use the fundamental theorem of arithmetic to test the results.
        // This way we know onHeader() has been called the right number of
        // times with the right arguments, and so onHeaders().
        final int[] primes = new int[]{2, 3, 5, 7, 11};
        int value = 1;
        for (int prime : primes)
            value *= prime;

        final AtomicInteger params = new AtomicInteger(1);
        ServerParser parser = new ServerParser(new ServerParser.Listener.Adapter()
        {
            @Override
            public void onHeader(int request, HttpField field)
            {
                Assert.assertEquals(id, request);
                switch (field.getName())
                {
                    case shortShortName:
                        Assert.assertEquals(shortShortValue, field.getValue());
                        params.set(params.get() * primes[0]);
                        break;
                    case shortLongName:
                        Assert.assertEquals(shortLongValue, field.getValue());
                        params.set(params.get() * primes[1]);
                        break;
                    case longShortName:
                        Assert.assertEquals(longShortValue, field.getValue());
                        params.set(params.get() * primes[2]);
                        break;
                    default:
                        Assert.assertEquals(longLongName, field.getName());
                        Assert.assertEquals(longLongValue, field.getValue());
                        params.set(params.get() * primes[3]);
                        break;
                }
            }

            @Override
            public void onHeaders(int request)
            {
                Assert.assertEquals(id, request);
                params.set(params.get() * primes[4]);
            }
        });

        for (ByteBuffer buffer : result.getByteBuffers())
        {
            parser.parse(buffer);
            Assert.assertFalse(buffer.hasRemaining());
        }

        Assert.assertEquals(value, params.get());

        // Parse again byte by byte
        params.set(1);
        for (ByteBuffer buffer : result.getByteBuffers())
        {
            buffer.flip();
            while (buffer.hasRemaining())
                parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
            Assert.assertFalse(buffer.hasRemaining());
        }

        Assert.assertEquals(value, params.get());
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

    private void testGenerateRequestContent(final int contentLength) throws Exception
    {
        ByteBuffer content = ByteBuffer.allocate(contentLength);

        ByteBufferPool byteBufferPool = new MappedByteBufferPool();
        ClientGenerator generator = new ClientGenerator(byteBufferPool);
        final int id = 13;
        Generator.Result result = generator.generateRequestContent(id, content, true, null);

        final AtomicInteger totalLength = new AtomicInteger();
        ServerParser parser = new ServerParser(new ServerParser.Listener.Adapter()
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
            }
        });

        for (ByteBuffer buffer : result.getByteBuffers())
        {
            parser.parse(buffer);
            Assert.assertFalse(buffer.hasRemaining());
        }

        // Parse again one byte at a time
        for (ByteBuffer buffer : result.getByteBuffers())
        {
            buffer.flip();
            while (buffer.hasRemaining())
                parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
            Assert.assertFalse(buffer.hasRemaining());
        }
    }
}
