//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class RawFrameBuilder
{
    public static void putOpFin(RetainableByteBuffer.Mutable buffer, byte opcode, boolean fin)
    {
        byte b = 0x00;
        if (fin)
        {
            b |= 0x80;
        }
        b |= opcode & 0x0F;
        buffer.put(b);
    }

    public static void putLengthAndMask(RetainableByteBuffer.Mutable buffer, int length, byte[] mask)
    {
        if (mask != null)
        {
            assertThat("Mask.length", mask.length, is(4));
            putLength(buffer, length, (mask != null));
            buffer.put(mask);
        }
        else
        {
            putLength(buffer, length, false);
        }
    }

    public static void mask(final byte[] data, final byte[] mask)
    {
        assertThat("Mask.length", mask.length, is(4));
        int len = data.length;
        for (int i = 0; i < len; i++)
        {
            data[i] ^= mask[i % 4];
        }
    }

    public static void putLength(RetainableByteBuffer.Mutable buffer, int length, boolean masked)
    {
        if (length < 0)
        {
            throw new IllegalArgumentException("Length cannot be negative");
        }
        byte b = (masked ? (byte)0x80 : 0x00);

        // write the uncompressed length
        if (length > 0xFF_FF)
        {
            buffer.put((byte)(b | 0x7F));
            buffer.put((byte)0x00);
            buffer.put((byte)0x00);
            buffer.put((byte)0x00);
            buffer.put((byte)0x00);
            buffer.put((byte)((length >> 24) & 0xFF));
            buffer.put((byte)((length >> 16) & 0xFF));
            buffer.put((byte)((length >> 8) & 0xFF));
            buffer.put((byte)(length & 0xFF));
        }
        else if (length >= 0x7E)
        {
            buffer.put((byte)(b | 0x7E));
            buffer.put((byte)(length >> 8));
            buffer.put((byte)(length & 0xFF));
        }
        else
        {
            buffer.put((byte)(b | length));
        }
    }

    public static void putMask(ByteBuffer buffer, byte[] mask)
    {
        assertThat("Mask.length", mask.length, is(4));
        buffer.put(mask);
    }

    public static void putPayloadLength(RetainableByteBuffer.Mutable buffer, int length)
    {
        putLength(buffer, length, true);
    }

    public static byte[] buildFrame(byte opcode, String message, boolean masked)
    {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        return buildFrame(opcode, bytes, masked);
    }

    public static byte[] buildFrame(byte opcode, byte[] bytes, boolean masked)
    {
        return buildFrame(opcode, bytes, masked, true);
    }

    public static byte[] buildFrame(byte opcode, byte[] bytes, boolean masked, boolean fin)
    {
        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.allocate(2048, false);
        RawFrameBuilder.putOpFin(buffer, opcode, fin);
        putLength(buffer, bytes.length, masked);
        if (masked)
        {
            byte[] mask = new byte[4];
            // ThreadLocalRandom.current().nextBytes(mask);
            buffer.put(mask);
            mask(bytes, mask);
        }
        buffer.put(bytes);
        return buffer.getArray();
    }

    public static byte[] buildText(String message, boolean masked)
    {
        return buildFrame(OpCode.TEXT, message, masked);
    }

    public static byte[] buildClose(CloseStatus status, boolean masked)
    {
        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.allocate(2048, false);

        byte[] bytes = status == null ? null : BufferUtil.toArray(status.asPayloadBuffer());
        RawFrameBuilder.putOpFin(buffer, OpCode.CLOSE, true);
        putLength(buffer, bytes == null ? 0 : bytes.length, masked);
        if (masked)
        {
            byte[] mask = new byte[4];
            // ThreadLocalRandom.current().nextBytes(mask);
            buffer.put(mask);
            mask(bytes, mask);
        }
        if (bytes != null)
            buffer.put(bytes);
        return buffer.getArray();
    }
}
