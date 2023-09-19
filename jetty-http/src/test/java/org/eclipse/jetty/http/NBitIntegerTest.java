//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.compression.NBitIntegerDecoder;
import org.eclipse.jetty.http.compression.NBitIntegerEncoder;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("PointlessArithmeticExpression")
public class NBitIntegerTest
{
    private final NBitIntegerDecoder _decoder = new NBitIntegerDecoder();

    @Test
    public void testOctetsNeeded()
    {
        assertEquals(1, NBitIntegerEncoder.octetsNeeded(5, 10));
        assertEquals(3, NBitIntegerEncoder.octetsNeeded(5, 1337));
        assertEquals(1, NBitIntegerEncoder.octetsNeeded(8, 42));
        assertEquals(3, NBitIntegerEncoder.octetsNeeded(8, 1337));

        assertEquals(1, NBitIntegerEncoder.octetsNeeded(6, 62));
        assertEquals(2, NBitIntegerEncoder.octetsNeeded(6, 63));
        assertEquals(2, NBitIntegerEncoder.octetsNeeded(6, 64));
        assertEquals(3, NBitIntegerEncoder.octetsNeeded(6, 63 + 0x00 + 0x80 * 0x01));
        assertEquals(4, NBitIntegerEncoder.octetsNeeded(6, 63 + 0x00 + 0x80 * 0x80));
        assertEquals(5, NBitIntegerEncoder.octetsNeeded(6, 63 + 0x00 + 0x80 * 0x80 * 0x80));
    }

    @Test
    public void testEncode()
    {
        testEncode(6, 0, "00");
        testEncode(6, 1, "01");
        testEncode(6, 62, "3e");
        testEncode(6, 63, "3f00");
        testEncode(6, 63 + 1, "3f01");
        testEncode(6, 63 + 0x7e, "3f7e");
        testEncode(6, 63 + 0x7f, "3f7f");
        testEncode(6, 63 + 0x00 + 0x80 * 0x01, "3f8001");
        testEncode(6, 63 + 0x01 + 0x80 * 0x01, "3f8101");
        testEncode(6, 63 + 0x7f + 0x80 * 0x01, "3fFf01");
        testEncode(6, 63 + 0x00 + 0x80 * 0x02, "3f8002");
        testEncode(6, 63 + 0x01 + 0x80 * 0x02, "3f8102");
        testEncode(6, 63 + 0x7f + 0x80 * 0x7f, "3fFf7f");
        testEncode(6, 63 + 0x00 + 0x80 * 0x80, "3f808001");
        testEncode(6, 63 + 0x7f + 0x80 * 0x80 * 0x7f, "3fFf807f");
        testEncode(6, 63 + 0x00 + 0x80 * 0x80 * 0x80, "3f80808001");

        testEncode(8, 0, "00");
        testEncode(8, 1, "01");
        testEncode(8, 128, "80");
        testEncode(8, 254, "Fe");
        testEncode(8, 255, "Ff00");
        testEncode(8, 255 + 1, "Ff01");
        testEncode(8, 255 + 0x7e, "Ff7e");
        testEncode(8, 255 + 0x7f, "Ff7f");
        testEncode(8, 255 + 0x80, "Ff8001");
        testEncode(8, 255 + 0x00 + 0x80 * 0x80, "Ff808001");
    }

    public void testEncode(int n, int i, String expected)
    {
        ByteBuffer buf = BufferUtil.allocate(16);
        int p = BufferUtil.flipToFill(buf);
        if (n < 8)
            buf.put((byte)0x00);
        NBitIntegerEncoder.encode(buf, n, i);
        BufferUtil.flipToFlush(buf, p);
        String r = TypeUtil.toHexString(BufferUtil.toArray(buf));
        assertEquals(expected, r);

        assertEquals(expected.length() / 2, NBitIntegerEncoder.octetsNeeded(n, i));
    }

    @Test
    public void testDecode()
    {
        testDecode(6, 0, "00");
        testDecode(6, 1, "01");
        testDecode(6, 62, "3e");
        testDecode(6, 63, "3f00");
        testDecode(6, 63 + 1, "3f01");
        testDecode(6, 63 + 0x7e, "3f7e");
        testDecode(6, 63 + 0x7f, "3f7f");
        testDecode(6, 63 + 0x80, "3f8001");
        testDecode(6, 63 + 0x81, "3f8101");
        testDecode(6, 63 + 0x7f + 0x80 * 0x01, "3fFf01");
        testDecode(6, 63 + 0x00 + 0x80 * 0x02, "3f8002");
        testDecode(6, 63 + 0x01 + 0x80 * 0x02, "3f8102");
        testDecode(6, 63 + 0x7f + 0x80 * 0x7f, "3fFf7f");
        testDecode(6, 63 + 0x00 + 0x80 * 0x80, "3f808001");
        testDecode(6, 63 + 0x7f + 0x80 * 0x80 * 0x7f, "3fFf807f");
        testDecode(6, 63 + 0x00 + 0x80 * 0x80 * 0x80, "3f80808001");

        testDecode(8, 0, "00");
        testDecode(8, 1, "01");
        testDecode(8, 128, "80");
        testDecode(8, 254, "Fe");
        testDecode(8, 255, "Ff00");
        testDecode(8, 255 + 1, "Ff01");
        testDecode(8, 255 + 0x7e, "Ff7e");
        testDecode(8, 255 + 0x7f, "Ff7f");
        testDecode(8, 255 + 0x80, "Ff8001");
        testDecode(8, 255 + 0x00 + 0x80 * 0x80, "Ff808001");
    }

    public void testDecode(int n, int expected, String encoded)
    {
        ByteBuffer buf = ByteBuffer.wrap(TypeUtil.fromHexString(encoded));
        _decoder.setPrefix(n);
        assertEquals(expected, _decoder.decodeInt(buf));
    }

    @Test
    public void testEncodeExampleD11()
    {
        ByteBuffer buf = BufferUtil.allocate(16);
        int p = BufferUtil.flipToFill(buf);
        buf.put((byte)0x77);
        buf.put((byte)0xFF);
        NBitIntegerEncoder.encode(buf, 5, 10);
        BufferUtil.flipToFlush(buf, p);

        String r = TypeUtil.toHexString(BufferUtil.toArray(buf));

        assertEquals("77Ea", r);
    }

    @Test
    public void testDecodeExampleD11()
    {
        ByteBuffer buf = ByteBuffer.wrap(TypeUtil.fromHexString("77EaFF"));
        buf.position(1);
        _decoder.setPrefix(5);
        assertEquals(10, _decoder.decodeInt(buf));
    }

    @Test
    public void testEncodeExampleD12()
    {
        ByteBuffer buf = BufferUtil.allocate(16);
        int p = BufferUtil.flipToFill(buf);
        buf.put((byte)0x88);
        buf.put((byte)0x00);
        NBitIntegerEncoder.encode(buf, 5, 1337);
        BufferUtil.flipToFlush(buf, p);

        String r = TypeUtil.toHexString(BufferUtil.toArray(buf));
        assertEquals("881f9a0a", r);
    }

    @Test
    public void testDecodeExampleD12()
    {
        ByteBuffer buf = ByteBuffer.wrap(TypeUtil.fromHexString("881f9a0aff"));
        buf.position(1);
        _decoder.setPrefix(5);
        assertEquals(1337, _decoder.decodeInt(buf));
    }

    @Test
    public void testEncodeExampleD13()
    {
        ByteBuffer buf = BufferUtil.allocate(16);
        int p = BufferUtil.flipToFill(buf);
        buf.put((byte)0x88);
        buf.put((byte)0xFF);
        NBitIntegerEncoder.encode(buf, 8, 42);
        BufferUtil.flipToFlush(buf, p);

        String r = TypeUtil.toHexString(BufferUtil.toArray(buf));

        assertEquals("88Ff2a", r);
    }

    @Test
    public void testDecodeExampleD13()
    {
        ByteBuffer buf = ByteBuffer.wrap(TypeUtil.fromHexString("882aFf"));
        buf.position(1);
        _decoder.setPrefix(8);
        assertEquals(42, _decoder.decodeInt(buf));
    }
}
