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

import org.eclipse.jetty.util.Utf8StringBuilder;

public class HuffmanDecoder
{
    public static String decode(ByteBuffer buffer, int length) throws EncodingException
    {
        Utf8StringBuilder utf8 = new Utf8StringBuilder(length * 2);
        int node = 0;
        int current = 0;
        int bits = 0;

        for (int i = 0; i < length; i++)
        {
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
                        throw new EncodingException("EOS in content");

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
                    throw new EncodingException("Incorrect padding");

                node = lastNode;
                break;
            }

            utf8.append((byte)(0xFF & rowsym[node]));
            bits -= rowbits[node];
            node = 0;
        }

        if (node != 0)
            throw new EncodingException("Bad termination");

        return utf8.toCompleteString();
    }

    static final char EOS = HuffmanEncoder.EOS;
    static final char[] tree = HuffmanEncoder.tree;
    static final char[] rowsym = HuffmanEncoder.rowsym;
    static final byte[] rowbits = HuffmanEncoder.rowbits;

    private final Utf8StringBuilder _utf8 = new Utf8StringBuilder();
    private int _length = 0;
    private int _count = 0;
    private int _node = 0;
    private int _current = 0;
    private int _bits = 0;

    public void setLength(int length)
    {
        if (_count != 0)
            throw new IllegalStateException();
        _length = length;
    }

    public String decode(ByteBuffer buffer) throws EncodingException
    {
        for (; _count < _length; _count++)
        {
            if (!buffer.hasRemaining())
                return null;

            int b = buffer.get() & 0xFF;
            _current = (_current << 8) | b;
            _bits += 8;
            while (_bits >= 8)
            {
                int c = (_current >>> (_bits - 8)) & 0xFF;
                _node = tree[_node * 256 + c];
                if (rowbits[_node] != 0)
                {
                    if (rowsym[_node] == EOS)
                    {
                        reset();
                        throw new EncodingException("eos_in_content");
                    }

                    // terminal node
                    _utf8.append((byte)(0xFF & rowsym[_node]));
                    _bits -= rowbits[_node];
                    _node = 0;
                }
                else
                {
                    // non-terminal node
                    _bits -= 8;
                }
            }
        }

        while (_bits > 0)
        {
            int c = (_current << (8 - _bits)) & 0xFF;
            int lastNode = _node;
            _node = tree[_node * 256 + c];

            if (rowbits[_node] == 0 || rowbits[_node] > _bits)
            {
                int requiredPadding = 0;
                for (int i = 0; i < _bits; i++)
                {
                    requiredPadding = (requiredPadding << 1) | 1;
                }

                if ((c >> (8 - _bits)) != requiredPadding)
                    throw new EncodingException("incorrect_padding");

                _node = lastNode;
                break;
            }

            _utf8.append((byte)(0xFF & rowsym[_node]));
            _bits -= rowbits[_node];
            _node = 0;
        }

        if (_node != 0)
        {
            reset();
            throw new EncodingException("bad_termination");
        }

        String value = _utf8.toCompleteString();
        reset();
        return value;
    }

    public void reset()
    {
        _utf8.reset();
        _count = 0;
        _current = 0;
        _node = 0;
        _bits = 0;
    }
}
