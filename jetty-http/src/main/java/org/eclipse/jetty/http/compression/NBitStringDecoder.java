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

import org.eclipse.jetty.util.CharsetStringBuilder;

/**
 * <p>Used to decode string literals as described in RFC7541.</p>
 *
 * <p>The string literal representation consists of a single bit to indicate whether huffman encoding is used,
 * followed by the string byte length encoded with the n-bit integer representation also from RFC7541, and
 * the bytes of the string are directly after this.</p>
 *
 * <p>Characters which are illegal field-vchar values are replaced with
 * either ' ' or '?' as described in RFC9110</p>
 */
public class NBitStringDecoder
{
    private final NBitIntegerDecoder _integerDecoder;
    private final HuffmanDecoder _huffmanBuilder;
    private final CharsetStringBuilder.Iso88591StringBuilder _builder;
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

    public NBitStringDecoder()
    {
        _integerDecoder = new NBitIntegerDecoder();
        _huffmanBuilder = new HuffmanDecoder();
        _builder = new CharsetStringBuilder.Iso88591StringBuilder();
    }

    /**
     * Set the prefix length in of the string representation in bits.
     * A prefix of 6 means the string representation starts after the first 2 bits.
     * @param prefix the number of bits in the string prefix.
     */
    public void setPrefix(int prefix)
    {
        if (_state != State.PARSING)
            throw new IllegalStateException();
        _prefix = prefix;
    }

    /**
     * Decode a string from the buffer. If the buffer does not contain the complete string representation
     * then a value of null is returned to indicate that more data is needed to complete parsing.
     * This should be only after the prefix has been set with {@link #setPrefix(int)}.
     * @param buffer the buffer containing the encoded string.
     * @return the decoded string or null to indicate that more data is needed.
     * @throws ArithmeticException if the string length value overflows a int.
     * @throws EncodingException if the string encoding is invalid.
     */
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
                    _integerDecoder.setPrefix(_prefix - 1);
                    continue;

                case LENGTH:
                    _length = _integerDecoder.decodeInt(buffer);
                    if (_length < 0)
                        return null;
                    _state = State.VALUE;
                    _huffmanBuilder.setLength(_length);
                    continue;

                case VALUE:
                    String value = _huffman ? _huffmanBuilder.decode(buffer) : stringDecode(buffer);
                    if (value != null)
                        reset();
                    return value;

                default:
                    throw new IllegalStateException(_state.name());
            }
        }
    }

    private String stringDecode(ByteBuffer buffer)
    {
        for (; _count < _length; _count++)
        {
            if (!buffer.hasRemaining())
                return null;
            _builder.append(buffer.get());
        }

        return _builder.build();
    }

    public void reset()
    {
        _state = State.PARSING;
        _integerDecoder.reset();
        _huffmanBuilder.reset();
        _builder.reset();
        _prefix = 0;
        _count = 0;
        _length = 0;
        _huffman = false;
    }
}
