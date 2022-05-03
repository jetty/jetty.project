//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.qpack.internal.util;

import java.nio.ByteBuffer;

public class NBitStringParser
{
    private final NBitIntegerParser _integerParser;
    private final HuffmanDecoder _huffmanBuilder;
    private final StringBuilder _stringBuilder;
    private boolean _huffman;
    private int _count;
    private int _length;
    private int _prefix;

    private State _state = State.PARSING;

    private enum State
    {
        PARSING,
        LENGTH,
        VALUE
    }

    public NBitStringParser()
    {
        _integerParser = new NBitIntegerParser();
        _huffmanBuilder = new HuffmanDecoder();
        _stringBuilder = new StringBuilder();
    }

    public void setPrefix(int prefix)
    {
        if (_state != State.PARSING)
            throw new IllegalStateException();
        _prefix = prefix;
    }

    public String decode(ByteBuffer buffer) throws EncodingException
    {
        while (true)
        {
            switch (_state)
            {
                case PARSING:
                    byte firstByte = buffer.get(buffer.position());
                    _huffman = ((0x80 >>> (8 - _prefix)) & firstByte) != 0;
                    _state = State.LENGTH;
                    _integerParser.setPrefix(_prefix - 1);
                    continue;

                case LENGTH:
                    _length = _integerParser.decodeInt(buffer);
                    if (_length < 0)
                        return null;
                    _state = State.VALUE;
                    _huffmanBuilder.setLength(_length);
                    continue;

                case VALUE:
                    String value = _huffman ? _huffmanBuilder.decode(buffer) : asciiStringDecode(buffer);
                    if (value != null)
                        reset();
                    return value;

                default:
                    throw new IllegalStateException(_state.name());
            }
        }
    }

    private String asciiStringDecode(ByteBuffer buffer)
    {
        for (; _count < _length; _count++)
        {
            if (!buffer.hasRemaining())
                return null;
            _stringBuilder.append((char)(0x7F & buffer.get()));
        }
        return _stringBuilder.toString();
    }

    public void reset()
    {
        _state = State.PARSING;
        _integerParser.reset();
        _huffmanBuilder.reset();
        _stringBuilder.setLength(0);
        _prefix = 0;
        _count = 0;
        _length = 0;
        _huffman = false;
    }
}
