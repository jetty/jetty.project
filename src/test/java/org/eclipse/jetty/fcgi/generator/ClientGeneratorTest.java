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
        final String methodParamName = "REQUEST_METHOD";
        final String methodParamValue = "GET";
        fields.put(new Fields.Field(methodParamName, methodParamValue));
        final String uriParamName = "REQUEST_URI";
        // Be sure it's longer than 127 chars to test the large value
        final String uriParamValue = "/api/0.6/map?bbox=-64.217736,-31.456810,-64.187736,-31.432322,filler=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        fields.put(new Fields.Field(uriParamName, uriParamValue));
        final String protocolParamName = "SERVER_PROTOCOL";
        final String protocolParamValue = "HTTP/1.1";
        fields.put(new Fields.Field(protocolParamName, protocolParamValue));
        // Be sure it's longer than 127 chars to test the large name
        final String hostParamName = "FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210";
        final String hostParamValue = "api.openstreetmap.org";
        fields.put(new Fields.Field(hostParamName, hostParamValue));

        ByteBufferPool byteBufferPool = new MappedByteBufferPool();
        ClientGenerator generator = new ClientGenerator(byteBufferPool);
        ByteBuffer buffer = generator.generateRequestHeaders(13, fields);

        // Use the fundamental theorem of arithmetic to test the results.
        // This way we know onParam() has been called the right number of
        // times with the right arguments, and so onParams().
        final int[] primes = new int[]{2, 3, 5, 7, 11};
        int result = 1;
        for (int prime : primes)
            result *= prime;

        final AtomicInteger params = new AtomicInteger(1);
        ClientParser parser = new ClientParser(new ClientParser.Listener.Adapter()
        {
            @Override
            public void onParam(String name, String value)
            {
                switch (name)
                {
                    case methodParamName:
                        Assert.assertEquals(methodParamValue, value);
                        params.set(params.get() * primes[0]);
                        break;
                    case uriParamName:
                        Assert.assertEquals(uriParamValue, value);
                        params.set(params.get() * primes[1]);
                        break;
                    case protocolParamName:
                        Assert.assertEquals(protocolParamValue, value);
                        params.set(params.get() * primes[2]);
                        break;
                    case hostParamName:
                        Assert.assertEquals(hostParamValue, value);
                        params.set(params.get() * primes[3]);
                        break;
                    default:
                        throw new IllegalStateException();
                }
            }

            @Override
            public void onParams()
            {
                params.set(params.get() * primes[4]);
            }
        });

        parser.parse(buffer);

        Assert.assertFalse(buffer.hasRemaining());
        Assert.assertEquals(result, params.get());

        // Parse again byte by byte
        buffer.flip();
        params.set(1);
        while (buffer.hasRemaining())
            parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));

        Assert.assertFalse(buffer.hasRemaining());
        Assert.assertEquals(result, params.get());

    }
}
