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

package org.eclipse.jetty.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * <p>Build a string from a sequence of bytes.</p>
 * <p>Implementations of this interface are optimized for processing a mix of calls to already decoded
 * character based appends (e.g. {@link #append(char)} and calls to undecoded byte methods (e.g. {@link #append(byte)}.
 * This is particularly useful for decoding % encoded strings that are mostly already decoded but may contain
 * escaped byte sequences that are not decoded.  The standard {@link CharsetDecoder} API is not well suited for this
 * use-case.</p>
 * <p>Any coding errors in the string will be reported by a {@link CharacterCodingException} thrown
 * from the {@link #takeString()} method.</p>
 * @see Utf8StringBuilder for UTF-8 decoding with replacement of coding errors and/or fast fail behaviour.
 * @see CharsetDecoder for decoding arbitrary {@link Charset}s with control over {@link CodingErrorAction}.
 */
public interface CharsetStringBuilder
{
    void append(byte b);

    void append(char c);

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

    default void append(CharSequence chars, int offset, int length)
    {
        int end = offset + length;
        for (int i = offset; i < end; i++)
            append(chars.charAt(i));
    }

    default void append(ByteBuffer buf)
    {
        int end = buf.position() + buf.remaining();
        while (buf.position() < end)
            append(buf.get());
    }

    /**
     * @return The decoded built string.
     * @throws CharacterCodingException If the bytes cannot be correctly decoded.
     */
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

        // Use a CharsetDecoder that defaults to CodingErrorAction#REPORT
        return new DecoderStringBuilder(charset.newDecoder());
    }

    /**
     * Extended Utf8StringBuilder that mimics {@link CodingErrorAction#REPORT} behaviour
     * for {@link CharsetStringBuilder} methods.
     */
    class ReportingUtf8StringBuilder extends Utf8StringBuilder
    {
        @Override
        public String toCompleteString()
        {
            if (hasCodingErrors())
                throw new RuntimeException(new CharacterCodingException());
            return super.toCompleteString();
        }

        @Override
        public String takeString() throws CharacterCodingException
        {
            if (hasCodingErrors())
                throw new CharacterCodingException();
            return super.takeString();
        }
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
        public void append(char c)
        {
            _builder.append(c);
        }

        @Override
        public void append(CharSequence chars, int offset, int length)
        {
            _builder.append(chars, offset, length);
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
        public void append(char c)
        {
            _builder.append(c);
        }

        @Override
        public void append(CharSequence chars, int offset, int length)
        {
            _builder.append(chars, offset, length);
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
        private final StringBuilder _stringBuilder = new StringBuilder(32);
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
        public void append(char c)
        {
            if (_buffer.position() > 0)
            {
                try
                {
                    // Append any data already in the decoder
                    _stringBuilder.append(_decoder.decode(_buffer.flip()));
                    _buffer.clear();
                }
                catch (CharacterCodingException e)
                {
                    // This will be thrown only if the decoder is configured to REPORT,
                    // otherwise errors will be ignored or replaced and we will not catch here.
                    throw new RuntimeException(e);
                }
            }
            _stringBuilder.append(c);
        }

        @Override
        public void append(CharSequence chars, int offset, int length)
        {
            if (_buffer.position() > 0)
            {
                try
                {
                    // Append any data already in the decoder
                    _stringBuilder.append(_decoder.decode(_buffer.flip()));
                    _buffer.clear();
                }
                catch (CharacterCodingException e)
                {
                    // This will be thrown only if the decoder is configured to REPORT,
                    // otherwise errors will be ignored or replaced and we will not catch here.
                    throw new RuntimeException(e);
                }
            }
            _stringBuilder.append(chars, offset, offset + length);
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
            try
            {
                if (_buffer.position() > 0)
                {
                    CharSequence decoded = _decoder.decode(_buffer.flip());
                    _buffer.clear();
                    if (_stringBuilder.isEmpty())
                        return decoded.toString();
                    _stringBuilder.append(decoded);
                }
                return _stringBuilder.toString();
            }
            finally
            {
                _stringBuilder.setLength(0);
            }
        }
    }
}


