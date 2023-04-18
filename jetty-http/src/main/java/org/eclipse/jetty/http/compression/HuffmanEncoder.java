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

import org.eclipse.jetty.http.HttpTokens;

import static org.eclipse.jetty.http.compression.Huffman.CODES;
import static org.eclipse.jetty.http.compression.Huffman.LCCODES;

public class HuffmanEncoder
{
    private HuffmanEncoder()
    {
    }

    public static int octetsNeeded(String s)
    {
        return octetsNeeded(CODES, s);
    }

    public static int octetsNeeded(byte[] b)
    {
        int needed = 0;
        for (byte value : b)
        {
            int c = 0xFF & value;
            needed += CODES[c][1];
        }
        return (needed + 7) / 8;
    }

    public static void encode(ByteBuffer buffer, String s)
    {
        encode(CODES, buffer, s);
    }

    public static void encode(ByteBuffer buffer, byte[] b)
    {
        encode(CODES, buffer, b);
    }

    public static int octetsNeededLowercase(String s)
    {
        return octetsNeeded(LCCODES, s);
    }

    public static void encodeLowercase(ByteBuffer buffer, String s)
    {
        encode(LCCODES, buffer, s);
    }

    private static int octetsNeeded(final int[][] table, String s)
    {
        int needed = 0;
        int len = s.length();
        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(i);
            if (HttpTokens.isIllegalFieldVchar(c))
                return -1;
            needed += table[c][1];
        }

        return (needed + 7) / 8;
    }

    /**
     * @param table The table to encode by
     * @param buffer The buffer to encode to
     * @param s The string to encode
     */
    private static void encode(final int[][] table, ByteBuffer buffer, String s)
    {
        long current = 0;
        int n = 0;
        int len = s.length();
        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(i);
            if (HttpTokens.isIllegalFieldVchar(c))
                 throw new IllegalArgumentException();
            int code = table[c][0];
            int bits = table[c][1];

            current <<= bits;
            current |= code;
            n += bits;

            while (n >= 8)
            {
                n -= 8;
                buffer.put((byte)(current >> n));
            }
        }

        if (n > 0)
        {
            current <<= (8 - n);
            current |= (0xFF >>> n);
            buffer.put((byte)(current));
        }
    }

    private static void encode(final int[][] table, ByteBuffer buffer, byte[] b)
    {
        long current = 0;
        int n = 0;

        for (byte value : b)
        {
            int i = 0xFF & value;
            int code = table[i][0];
            int bits = table[i][1];

            current <<= bits;
            current |= code;
            n += bits;

            while (n >= 8)
            {
                n -= 8;
                buffer.put((byte)(current >> n));
            }
        }

        if (n > 0)
        {
            current <<= (8 - n);
            current |= (0xFF >>> n);
            buffer.put((byte)(current));
        }
    }
}
