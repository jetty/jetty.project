/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
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
        Charset charset = Charset.forName("ISO-8859-1");
        ByteBuffer buffer = ByteBuffer.wrap((name + value).getBytes(charset));
        int iterations = 100_000_000;

        long begin = System.nanoTime();
        for (int i = 0; i < iterations; ++i)
        {
            byte[] nameBytes = new byte[name.length()];
            buffer.get(nameBytes);
            String name2 = new String(nameBytes, charset);
            Assert.assertEquals(name2, name);

            byte[] valueBytes = new byte[value.length()];
            buffer.get(valueBytes);
            String value2 = new String(valueBytes, charset);
            Assert.assertEquals(value2, value);

            buffer.flip();
        }
        long end = System.nanoTime();
        System.err.printf("parse time: %d%n", TimeUnit.NANOSECONDS.toMillis(end - begin));

        Map<ByteBuffer, String> map = new HashMap<>();
        map.put(ByteBuffer.wrap(name.getBytes(charset)), name);
        map.put(ByteBuffer.wrap(value.getBytes(charset)), value);
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
