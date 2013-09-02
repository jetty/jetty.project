//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.fcgi.parser.ClientParser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.Fields;
import org.junit.Assert;
import org.junit.Test;

public class ClientGeneratorTest
{
    @Test
    public void testGenerateRequestWithoutContent() throws Exception
    {
        Fields fields = new Fields();

        // Short name, short value
        final String shortShortName = "REQUEST_METHOD";
        final String shortShortValue = "GET";
        fields.put(new Fields.Field(shortShortName, shortShortValue));

        // Short name, long value
        final String shortLongName = "REQUEST_URI";
        // Be sure it's longer than 127 chars to test the large value
        final String shortLongValue = "/api/0.6/map?bbox=-64.217736,-31.456810,-64.187736,-31.432322,filler=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        fields.put(new Fields.Field(shortLongName, shortLongValue));

        // Long name, short value
        // Be sure it's longer than 127 chars to test the large name
        final String longShortName = "FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210";
        final String longShortValue = "api.openstreetmap.org";
        fields.put(new Fields.Field(longShortName, longShortValue));

        // Long name, long value
        char[] chars = new char[ClientGenerator.MAX_PARAM_LENGTH];
        Arrays.fill(chars, 'z');
        final String longLongName = new String(chars);
        final String longLongValue = longLongName;
        fields.put(new Fields.Field(longLongName, longLongValue));

        ByteBufferPool byteBufferPool = new MappedByteBufferPool();
        ClientGenerator generator = new ClientGenerator(byteBufferPool);
        Generator.Result result = generator.generateRequestHeaders(13, fields, null);

        // Use the fundamental theorem of arithmetic to test the results.
        // This way we know onParam() has been called the right number of
        // times with the right arguments, and so onParams().
        final int[] primes = new int[]{2, 3, 5, 7, 11};
        int value = 1;
        for (int prime : primes)
            value *= prime;

        final AtomicInteger params = new AtomicInteger(1);
        ClientParser parser = new ClientParser(new ClientParser.Listener.Adapter()
        {
            @Override
            public void onParam(String name, String value)
            {
                switch (name)
                {
                    case shortShortName:
                        Assert.assertEquals(shortShortValue, value);
                        params.set(params.get() * primes[0]);
                        break;
                    case shortLongName:
                        Assert.assertEquals(shortLongValue, value);
                        params.set(params.get() * primes[1]);
                        break;
                    case longShortName:
                        Assert.assertEquals(longShortValue, value);
                        params.set(params.get() * primes[2]);
                        break;
                    default:
                        Assert.assertEquals(longLongName, name);
                        Assert.assertEquals(longLongValue, value);
                        params.set(params.get() * primes[3]);
                        break;
                }
            }

            @Override
            public void onParams()
            {
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
}
