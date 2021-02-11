//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.qpack;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Utf8StringBuilder;

public class HuffmanDecoder
{
    static final char EOS = Huffman.EOS;
    static final char[] tree = Huffman.tree;
    static final char[] rowsym = Huffman.rowsym;
    static final byte[] rowbits = Huffman.rowbits;

    private final Utf8StringBuilder utf8 = new Utf8StringBuilder();
    private int count = 0;
    private int node = 0;
    private int current = 0;
    private int bits = 0;

    public String decode(ByteBuffer buffer, int length) throws QpackException.CompressionException
    {
        for (; count < length; count++)
        {
            if (!buffer.hasRemaining())
                return null;

            int b = buffer.get() & 0xFF;
            current = (current << 8) | b;
            bits += 8;
            while (bits >= 8)
            {
                int c = (current >>> (bits - 8)) & 0xFF;
                node = tree[node * 256 + c];
                if (rowbits[node] != 0)
                {
                    if (rowsym[node] == EOS)
                    {
                        reset();
                        throw new QpackException.CompressionException("EOS in content");
                    }

                    // terminal node
                    utf8.append((byte)(0xFF & rowsym[node]));
                    bits -= rowbits[node];
                    node = 0;
                }
                else
                {
                    // non-terminal node
                    bits -= 8;
                }
            }
        }

        while (bits > 0)
        {
            int c = (current << (8 - bits)) & 0xFF;
            int lastNode = node;
            node = tree[node * 256 + c];

            if (rowbits[node] == 0 || rowbits[node] > bits)
            {
                int requiredPadding = 0;
                for (int i = 0; i < bits; i++)
                {
                    requiredPadding = (requiredPadding << 1) | 1;
                }

                if ((c >> (8 - bits)) != requiredPadding)
                    throw new QpackException.CompressionException("Incorrect padding");

                node = lastNode;
                break;
            }

            utf8.append((byte)(0xFF & rowsym[node]));
            bits -= rowbits[node];
            node = 0;
        }

        if (node != 0)
            throw new QpackException.CompressionException("Bad termination");

        return utf8.toString();
    }

    public void reset()
    {
        utf8.reset();
        count = 0;
        current = 0;
        node = 0;
        bits = 0;
    }
}
