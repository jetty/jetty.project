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

package org.eclipse.jetty.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Build a string from a sequence of bytes.
 */
public interface CharsetStringBuilder
{
    void append(byte b);

    default void append(byte[] bytes)
    {
        append(bytes, 0, bytes.length);
    }

    default void append(byte[] b, int offset, int length)
    {
        int end = offset + length;
        for (int i = offset; i < end; i++)
            append(b[i]);
    }

    default void append(ByteBuffer buf)
    {
        int end = buf.position() + buf.remaining();
        while (buf.position() < end)
            append(buf.get());
    }

    String takeString() throws CharacterCodingException;

    static CharsetStringBuilder forCharset(Charset charset)
    {
        Objects.requireNonNull(charset);
        if (charset == StandardCharsets.UTF_8)
            return new Utf8StringBuilder();
        if (charset == StandardCharsets.ISO_8859_1)
            return new Iso8859StringBuilder();
        if (charset == StandardCharsets.US_ASCII)
            return new UsAsciiStringBuilder();
        return new DecoderStringBuilder(charset.newDecoder());
    }

    class Iso8859StringBuilder implements CharsetStringBuilder
    {
        private final StringBuilder _builder = new StringBuilder();

        @Override
        public void append(byte b)
        {
            _builder.append((char)(0xff & b));
        }

        @Override
        public String takeString()
        {
            String s = _builder.toString();
            _builder.setLength(0);
            return s;
        }
    }
    
    class UsAsciiStringBuilder implements CharsetStringBuilder
    {
        private final StringBuilder _builder = new StringBuilder();

        @Override
        public void append(byte b)
        {
            if (b < 0)
                throw new IllegalArgumentException();
            _builder.append((char)b);
        }

        @Override
        public String takeString()
        {
            String s = _builder.toString();
            _builder.setLength(0);
            return s;
        }
    }

    class DecoderStringBuilder implements CharsetStringBuilder
    {
        private final CharsetDecoder _decoder;
        private ByteBuffer _buffer = ByteBuffer.allocate(32);
        
        public DecoderStringBuilder(CharsetDecoder charsetDecoder)
        {
            _decoder = charsetDecoder;
        }

        private void ensureSpace(int needed)
        {
            int space = _buffer.remaining();
            if (space < needed)
            {
                int position = _buffer.position();
                _buffer = ByteBuffer.wrap(Arrays.copyOf(_buffer.array(), _buffer.capacity() + needed - space + 32)).position(position);
            }
        }

        @Override
        public void append(byte b)
        {
            ensureSpace(1);
            _buffer.put(b);
        }

        @Override
        public void append(byte[] b, int offset, int length)
        {
            ensureSpace(length);
            _buffer.put(b, offset, length);
        }

        @Override
        public void append(ByteBuffer buf)
        {
            ensureSpace(buf.remaining());
            _buffer.put(buf);
        }

        @Override
        public String takeString() throws CharacterCodingException
        {
            CharSequence chars = _decoder.decode(_buffer.flip());
            _buffer.clear();
            return chars.toString();
        }
    }
}


