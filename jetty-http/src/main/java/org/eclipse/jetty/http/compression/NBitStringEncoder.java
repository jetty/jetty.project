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

public class NBitStringEncoder
{
    private NBitStringEncoder()
    {
    }

    public static int octetsNeeded(int prefix, String value, boolean huffman)
    {
        if (prefix <= 0 || prefix > 8)
            throw new IllegalArgumentException();

        int contentPrefix = (prefix == 1) ? 8 : prefix - 1;
        int encodedValueSize = huffman ? HuffmanEncoder.octetsNeeded(value) : value.length();
        int encodedLengthSize = NBitIntegerEncoder.octetsNeeded(contentPrefix, encodedValueSize);

        // If prefix was 1, then we count an extra byte needed for the prefix.
        return encodedLengthSize + encodedValueSize + (prefix == 1 ? 1 : 0);
    }

    public static void encode(ByteBuffer buffer, int prefix, String value, boolean huffman)
    {
        if (prefix <= 0 || prefix > 8)
            throw new IllegalArgumentException();

        byte huffmanFlag = huffman ? (byte)(0x01 << (prefix - 1)) : (byte)0x00;
        if (prefix == 8)
        {
            buffer.put(huffmanFlag);
        }
        else
        {
            int p = buffer.position() - 1;
            buffer.put(p, (byte)(buffer.get(p) | huffmanFlag));
        }

        // Start encoding size & content in rest of prefix.
        // If prefix was 1 we set it back to 8 to indicate to start on a new byte.
        prefix = (prefix == 1) ? 8 : prefix - 1;

        if (huffman)
        {
            int encodedValueSize = HuffmanEncoder.octetsNeeded(value);
            NBitIntegerEncoder.encode(buffer, prefix, encodedValueSize);
            HuffmanEncoder.encode(buffer, value);
        }
        else
        {
            int encodedValueSize = value.length();
            NBitIntegerEncoder.encode(buffer, prefix, encodedValueSize);
            for (int i = 0; i < encodedValueSize; i++)
            {
                char c = value.charAt(i);
                c = HttpTokens.sanitizeFieldVchar(c);
                buffer.put((byte)c);
            }
        }
    }
}
