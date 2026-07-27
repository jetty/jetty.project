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

package org.eclipse.jetty.http.compression;

import java.nio.ByteBuffer;

import static org.eclipse.jetty.http.compression.Huffman.BITS_MASK;
import static org.eclipse.jetty.http.compression.Huffman.CODE_SHIFT;
import static org.eclipse.jetty.http.compression.Huffman.EOS;
import static org.eclipse.jetty.http.compression.Huffman.PACKED_CODES;
import static org.eclipse.jetty.http.compression.Huffman.PACKED_LCCODES;

/**
 * <p>Used to encode strings Huffman encoding.</p>
 *
 * <p>Characters are encoded with ISO-8859-1, if any multi-byte characters or
 * control characters are present the encoder will throw {@link EncodingException}.</p>
 */
public class HuffmanEncoder
{
    private HuffmanEncoder()
    {
    }

    /**
     * @param s the string to encode.
     * @return the number of octets needed to encode the string, or -1 if it cannot be encoded.
     */
    public static int octetsNeeded(String s)
    {
        return octetsNeeded(PACKED_CODES, s);
    }

    /**
     * @param b the byte array to encode.
     * @return the number of octets needed to encode the bytes, or -1 if it cannot be encoded.
     */
    public static int octetsNeeded(byte[] b)
    {
        int needed = 0;
        for (byte value : b)
        {
            int c = 0xFF & value;
            needed += (int)(PACKED_CODES[c] & BITS_MASK);
        }
        return (needed + 7) / 8;
    }

    /**
     * @param buffer the buffer to encode into.
     * @param s the string to encode.
     */
    public static void encode(ByteBuffer buffer, String s)
    {
        encode(PACKED_CODES, buffer, s);
    }

    /**
     * @param s the string to encode in lowercase.
     * @return the number of octets needed to encode the string, or -1 if it cannot be encoded.
     */
    public static int octetsNeededLowerCase(String s)
    {
        return octetsNeeded(PACKED_LCCODES, s);
    }

    /**
     * @param buffer the buffer to encode into in lowercase.
     * @param s the string to encode.
     */
    public static void encodeLowerCase(ByteBuffer buffer, String s)
    {
        encode(PACKED_LCCODES, buffer, s);
    }

    private static int octetsNeeded(final long[] table, String s)
    {
        int needed = 0;
        int len = s.length();
        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(i);
            if (isIllegalHuffmanChar(c))
                return -1;
            needed += (int)(table[c] & BITS_MASK);
        }

        return (needed + 7) / 8;
    }

    /**
     * @param table The table to encode by
     * @param buffer The buffer to encode to
     * @param s The string to encode
     */
    private static void encode(final long[] table, ByteBuffer buffer, String s)
    {
        long current = 0;
        int n = 0;
        int len = s.length();
        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(i);
            if (isIllegalHuffmanChar(c))
                 throw new IllegalArgumentException();
            long packed = table[c];
            int bits = (int)(packed & BITS_MASK);

            current <<= bits;
            current |= packed >>> CODE_SHIFT;
            n += bits;

            // Codes are at most 30 bits, so letting up to 31 bits accumulate
            // keeps the accumulator within 64 bits, and lets 4 octets at a
            // time be written with a single put rather than one put each.
            if (n >= 32)
            {
                n -= 32;
                buffer.putInt((int)(current >>> n));
            }
        }

        while (n >= 8)
        {
            n -= 8;
            buffer.put((byte)(current >> n));
        }

        if (n > 0)
        {
            current <<= (8 - n);
            current |= (0xFF >>> n);
            buffer.put((byte)(current));
        }
    }

    /**
     * Tests whether the given character is valid for Huffman encoding.
     * @param c the character to test.
     * @return true if the character is illegal, false otherwise.
     */
    private static boolean isIllegalHuffmanChar(char c)
    {
        return c >= EOS;
    }
}
