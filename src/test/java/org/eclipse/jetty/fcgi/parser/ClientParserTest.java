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

package org.eclipse.jetty.fcgi.parser;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.fcgi.generator.ServerGenerator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.Fields;
import org.junit.Assert;
import org.junit.Test;

public class ClientParserTest
{
    @Test
    public void testParseResponseHeaders() throws Exception
    {
        final int id = 13;
        Fields fields = new Fields();

        final String statusName = "Status";
        final int code = 200;
        final String contentTypeName = "Content-Type";
        final String contentTypeValue = "text/html;charset=utf-8";
        fields.put(contentTypeName, contentTypeValue);

        ByteBufferPool byteBufferPool = new MappedByteBufferPool();
        ServerGenerator generator = new ServerGenerator(byteBufferPool);
        Generator.Result result = generator.generateResponseHeaders(id, code, "OK", fields, null);

        // Use the fundamental theorem of arithmetic to test the results.
        // This way we know onHeader() has been called the right number of
        // times with the right arguments, and so onHeaders().
        final int[] primes = new int[]{2, 3, 5};
        int value = 1;
        for (int prime : primes)
            value *= prime;

        final AtomicInteger params = new AtomicInteger(1);
        ClientParser parser = new ClientParser(new Parser.Listener.Adapter()
        {
            @Override
            public void onHeader(int request, String name, String value)
            {
                Assert.assertEquals(id, request);
                switch (name)
                {
                    case statusName:
                        Assert.assertTrue(value.startsWith(String.valueOf(code)));
                        params.set(params.get() * primes[0]);
                        break;
                    case contentTypeName:
                        Assert.assertEquals(contentTypeValue, value);
                        params.set(params.get() * primes[1]);
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
}
