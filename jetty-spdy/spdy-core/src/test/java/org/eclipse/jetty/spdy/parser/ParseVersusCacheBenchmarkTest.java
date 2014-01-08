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

package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class ParseVersusCacheBenchmarkTest
{
    @Ignore
    @Test
    public void testParseVersusCache() throws Exception
    {
        // The parser knows the header name and value lengths, so it creates strings
        // out of the bytes; however, this involves creating a byte[] copy the bytes,
        // and creating a new String.
        // The alternative is to use a cache<ByteBuffer, String>. Is that faster ?
        // See also: http://jeremymanson.blogspot.com/2008/04/immutability-in-java.html

        String name = "Content-Type";
        String value = "application/octect-stream";
        ByteBuffer buffer = ByteBuffer.wrap((name + value).getBytes(StandardCharsets.ISO_8859_1));
        int iterations = 100_000_000;

        long begin = System.nanoTime();
        for (int i = 0; i < iterations; ++i)
        {
            byte[] nameBytes = new byte[name.length()];
            buffer.get(nameBytes);
            String name2 = new String(nameBytes, StandardCharsets.ISO_8859_1);
            Assert.assertEquals(name2, name);

            byte[] valueBytes = new byte[value.length()];
            buffer.get(valueBytes);
            String value2 = new String(valueBytes, StandardCharsets.ISO_8859_1);
            Assert.assertEquals(value2, value);

            buffer.flip();
        }
        long end = System.nanoTime();
        System.err.printf("parse time: %d%n", TimeUnit.NANOSECONDS.toMillis(end - begin));

        Map<ByteBuffer, String> map = new HashMap<>();
        map.put(ByteBuffer.wrap(name.getBytes(StandardCharsets.ISO_8859_1)), name);
        map.put(ByteBuffer.wrap(value.getBytes(StandardCharsets.ISO_8859_1)), value);
        final Map<ByteBuffer, String> cache = Collections.unmodifiableMap(map);

        begin = System.nanoTime();
        for (int i = 0; i < iterations; ++i)
        {
            buffer.limit(buffer.position() + name.length());
            String name2 = cache.get(buffer);
            Assert.assertEquals(name2, name);

            buffer.position(buffer.limit());
            buffer.limit(buffer.position() + value.length());
            String value2 = cache.get(buffer);
            Assert.assertEquals(value2, value);

            buffer.position(buffer.limit());
            buffer.flip();
        }
        end = System.nanoTime();
        System.err.printf("cache time: %d%n", TimeUnit.NANOSECONDS.toMillis(end - begin));
    }
}
