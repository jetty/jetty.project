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

package org.eclipse.jetty.http.compression;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpTokens;

import static org.eclipse.jetty.http.compression.Huffman.CODES;
import static org.eclipse.jetty.http.compression.Huffman.LCCODES;

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
        return octetsNeeded(CODES, s);
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
            needed += CODES[c][1];
        }
        return (needed + 7) / 8;
    }

    /**
     * @param buffer the buffer to encode into.
     * @param s the string to encode.
     */
    public static void encode(ByteBuffer buffer, String s)
    {
        encode(CODES, buffer, s);
    }

    /**
     * @param s the string to encode in lowercase.
     * @return the number of octets needed to encode the string, or -1 if it cannot be encoded.
     */
    public static int octetsNeededLowerCase(String s)
    {
        return octetsNeeded(LCCODES, s);
    }

    /**
     * @param buffer the buffer to encode into in lowercase.
     * @param s the string to encode.
     */
    public static void encodeLowerCase(ByteBuffer buffer, String s)
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
}
