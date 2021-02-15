//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SearchPatternTest
{

    @Test
    public void testBasicSearch()
    {
        byte[] p1 = "truth".getBytes(StandardCharsets.US_ASCII);
        byte[] p2 = "evident".getBytes(StandardCharsets.US_ASCII);
        byte[] p3 = "we".getBytes(StandardCharsets.US_ASCII);
        byte[] d = "we hold these truths to be self evident".getBytes(StandardCharsets.US_ASCII);

        // Testing Compiled Pattern p1 "truth"
        SearchPattern sp1 = SearchPattern.compile(p1);
        assertEquals(14, sp1.match(d, 0, d.length));
        assertEquals(14, sp1.match(d, 14, p1.length));
        assertEquals(14, sp1.match(d, 14, p1.length + 1));
        assertEquals(-1, sp1.match(d, 14, p1.length - 1));
        assertEquals(-1, sp1.match(d, 15, d.length - 15));

        // Testing Compiled Pattern p2 "evident"
        SearchPattern sp2 = SearchPattern.compile(p2);
        assertEquals(32, sp2.match(d, 0, d.length));
        assertEquals(32, sp2.match(d, 32, p2.length));
        assertEquals(32, sp2.match(d, 32, p2.length));
        assertEquals(-1, sp2.match(d, 32, p2.length - 1));
        assertEquals(-1, sp2.match(d, 33, d.length - 33));

        // Testing Compiled Pattern p3 "evident"
        SearchPattern sp3 = SearchPattern.compile(p3);
        assertEquals(0, sp3.match(d, 0, d.length));
        assertEquals(0, sp3.match(d, 0, p3.length));
        assertEquals(0, sp3.match(d, 0, p3.length + 1));
        assertEquals(-1, sp3.match(d, 0, p3.length - 1));
        assertEquals(-1, sp3.match(d, 1, d.length - 1));
    }

    @Test
    public void testDoubleMatch()
    {
        byte[] p = "violent".getBytes(StandardCharsets.US_ASCII);
        byte[] d = "These violent delights have violent ends.".getBytes(StandardCharsets.US_ASCII);
        SearchPattern sp = SearchPattern.compile(p);
        assertEquals(6, sp.match(d, 0, d.length));
        assertEquals(-1, sp.match(d, 6, p.length - 1));
        assertEquals(28, sp.match(d, 7, d.length - 7));
        assertEquals(28, sp.match(d, 28, d.length - 28));
        assertEquals(-1, sp.match(d, 29, d.length - 29));
    }

    @Test
    public void testSearchInBinary()
    {
        byte[] random = new byte[8192];
        ThreadLocalRandom.current().nextBytes(random);
        // Arrays.fill(random,(byte)-67);
        String preamble = "Blah blah blah";
        String epilogue = "The End! Blah Blah Blah";

        ByteBuffer data = BufferUtil.allocate(preamble.length() + random.length + epilogue.length());
        BufferUtil.append(data, BufferUtil.toBuffer(preamble));
        BufferUtil.append(data, ByteBuffer.wrap(random));
        BufferUtil.append(data, BufferUtil.toBuffer(epilogue));

        SearchPattern sp = SearchPattern.compile("The End!");

        assertEquals(preamble.length() + random.length, sp.match(data.array(), data.arrayOffset() + data.position(), data.remaining()));
    }

    @Test
    public void testSearchBinaryKey()
    {
        byte[] random = new byte[8192];
        ThreadLocalRandom.current().nextBytes(random);
        byte[] key = new byte[64];
        ThreadLocalRandom.current().nextBytes(key);

        ByteBuffer data = BufferUtil.allocate(random.length + key.length);
        BufferUtil.append(data, ByteBuffer.wrap(random));
        BufferUtil.append(data, ByteBuffer.wrap(key));
        SearchPattern sp = SearchPattern.compile(key);

        assertEquals(random.length, sp.match(data.array(), data.arrayOffset() + data.position(), data.remaining()));
    }

    @Test
    public void testAlmostMatch()
    {
        byte[] p = "violent".getBytes(StandardCharsets.US_ASCII);
        byte[] d = "vio lent violen v iolent violin vioviolenlent viiolent".getBytes(StandardCharsets.US_ASCII);
        SearchPattern sp = SearchPattern.compile(p);
        assertEquals(-1, sp.match(d, 0, d.length));
    }

    @Test
    public void testOddSizedPatterns()
    {
        // Test Large Pattern
        byte[] p = "pneumonoultramicroscopicsilicovolcanoconiosis".getBytes(StandardCharsets.US_ASCII);
        byte[] d = "pneumon".getBytes(StandardCharsets.US_ASCII);
        SearchPattern sp = SearchPattern.compile(p);
        assertEquals(-1, sp.match(d, 0, d.length));

        // Test Single Character Pattern
        p = "s".getBytes(StandardCharsets.US_ASCII);
        d = "the cake is a lie".getBytes(StandardCharsets.US_ASCII);
        sp = SearchPattern.compile(p);
        assertEquals(10, sp.match(d, 0, d.length));
    }

    @Test
    public void testEndsWith()
    {
        byte[] p = "pneumonoultramicroscopicsilicovolcanoconiosis".getBytes(StandardCharsets.US_ASCII);
        byte[] d = "pneumonoultrami".getBytes(StandardCharsets.US_ASCII);
        SearchPattern sp = SearchPattern.compile(p);
        assertEquals(15, sp.endsWith(d, 0, d.length));

        p = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
        d = "abcdefghijklmnopqrstuvwxyzabcdefghijklmno".getBytes(StandardCharsets.US_ASCII);
        sp = SearchPattern.compile(p);
        assertEquals(0, sp.match(d, 0, d.length));
        assertEquals(-1, sp.match(d, 1, d.length - 1));
        assertEquals(15, sp.endsWith(d, 0, d.length));

        p = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
        d = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
        sp = SearchPattern.compile(p);
        assertEquals(0, sp.match(d, 0, d.length));
        assertEquals(26, sp.match(d, 1, d.length - 1));
        assertEquals(26, sp.endsWith(d, 0, d.length));

        //test no match
        p = "hello world".getBytes(StandardCharsets.US_ASCII);
        d = "there is definitely no match in here".getBytes(StandardCharsets.US_ASCII);
        sp = SearchPattern.compile(p);
        assertEquals(0, sp.endsWith(d, 0, d.length));

        //Test with range on array.
        p = "abcde".getBytes(StandardCharsets.US_ASCII);
        d = "?abc00000".getBytes(StandardCharsets.US_ASCII);
        sp = SearchPattern.compile(p);
        assertEquals(3, sp.endsWith(d, 0, 4));
    }

    @Test
    public void testStartsWithNoOffset()
    {
        testStartsWith("");
    }

    @Test
    public void testStartsWithOffset()
    {
        testStartsWith("abcdef");
    }

    private void testStartsWith(String offset)
    {
        byte[] p = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
        byte[] d = (offset + "ijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz").getBytes(StandardCharsets.US_ASCII);
        SearchPattern sp = SearchPattern.compile(p);
        assertEquals(18 + offset.length(), sp.match(d, offset.length(), d.length - offset.length()));
        assertEquals(-1, sp.match(d, offset.length() + 19, d.length - 19 - offset.length()));
        assertEquals(26, sp.startsWith(d, offset.length(), d.length - offset.length(), 8));

        p = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
        d = (offset + "ijklmnopqrstuvwxyNOMATCH").getBytes(StandardCharsets.US_ASCII);
        sp = SearchPattern.compile(p);
        assertEquals(0, sp.startsWith(d, offset.length(), d.length - offset.length(), 8));

        p = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
        d = (offset + "abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz").getBytes(StandardCharsets.US_ASCII);
        sp = SearchPattern.compile(p);
        assertEquals(26, sp.startsWith(d, offset.length(), d.length - offset.length(), 0));

        //test no match
        p = "hello world".getBytes(StandardCharsets.US_ASCII);
        d = (offset + "there is definitely no match in here").getBytes(StandardCharsets.US_ASCII);
        sp = SearchPattern.compile(p);
        assertEquals(0, sp.startsWith(d, offset.length(), d.length - offset.length(), 0));

        //test large pattern small buffer
        p = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
        d = (offset + "mnopqrs").getBytes(StandardCharsets.US_ASCII);
        sp = SearchPattern.compile(p);
        assertEquals(19, sp.startsWith(d, offset.length(), d.length - offset.length(), 12));

        //partial pattern
        p = "abcdef".getBytes(StandardCharsets.US_ASCII);
        d = (offset + "cde").getBytes(StandardCharsets.US_ASCII);
        sp = SearchPattern.compile(p);
        assertEquals(5, sp.startsWith(d, offset.length(), d.length - offset.length(), 2));
    }

    @Test
    public void testExampleFrom4673()
    {
        SearchPattern pattern = SearchPattern.compile("\r\n------WebKitFormBoundaryhXfFAMfUnUKhmqT8".getBytes(StandardCharsets.US_ASCII));
        byte[] data = new byte[]{
            118, 97, 108, 117, 101, 49,
            '\r', '\n', '-', '-', '-', '-',
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        };
        int length = 12;

        int partialMatch = pattern.endsWith(data, 0, length);
        System.err.println("match1: " + partialMatch);
    }
}
