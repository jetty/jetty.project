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

import org.eclipse.jetty.util.CharsetStringBuilder;

import static org.eclipse.jetty.http.compression.Huffman.rowbits;
import static org.eclipse.jetty.http.compression.Huffman.rowsym;

public class HuffmanDecoder
{
    public static String decode(ByteBuffer buffer, int length) throws EncodingException
    {
        HuffmanDecoder huffmanDecoder = new HuffmanDecoder();
        huffmanDecoder.setLength(length);
        String decoded = huffmanDecoder.decode(buffer);
        if (decoded == null)
            throw new EncodingException("invalid string encoding");

        huffmanDecoder.reset();
        return decoded;
    }

    private final CharsetStringBuilder.Iso8859StringBuilder _builder = new CharsetStringBuilder.Iso8859StringBuilder();
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
                _node = Huffman.tree[_node * 256 + c];
                if (rowbits[_node] != 0)
                {
                    if (rowsym[_node] == Huffman.EOS)
                    {
                        reset();
                        throw new EncodingException("eos_in_content");
                    }

                    // terminal node
                    int i = 0xFF & rowsym[_node];
                    i = Huffman.getReplacementCharacter(i);
                    _builder.append((byte)i);
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
            _node = Huffman.tree[_node * 256 + c];

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

            int i = 0xFF & rowsym[_node];
            i = Huffman.getReplacementCharacter(i);
            _builder.append((byte)i);
            _bits -= rowbits[_node];
            _node = 0;
        }

        if (_node != 0)
        {
            reset();
            throw new EncodingException("bad_termination");
        }

        String value = _builder.build();
        reset();
        return value;
    }

    public void reset()
    {
        _builder.reset();
        _count = 0;
        _current = 0;
        _node = 0;
        _bits = 0;
    }
}
