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
 * <p>Build a string from a sequence of bytes and/or characters.</p>
 * <p>Implementations of this interface are optimized for processing a mix of calls to already decoded
 * character based appends (e.g. {@link #append(char)} and calls to undecoded byte methods (e.g. {@link #append(byte)}.
 * This is particularly useful for decoding % encoded strings that are mostly already decoded but may contain
 * escaped byte sequences that are not decoded.  The standard {@link CharsetDecoder} API is not well suited for this
 * use-case.</p>
 * <p>Any coding errors in the string will be reported by a {@link CharacterCodingException} thrown
 * from the {@link #build()} method.</p>
 * @see Utf8StringBuilder for UTF-8 decoding with replacement of coding errors and/or fast fail behaviour.
 * @see CharsetDecoder for decoding arbitrary {@link Charset}s with control over {@link CodingErrorAction}.
 */
public interface CharsetStringBuilder
{
    /**
     * @param b An encoded byte to append
     */
    void append(byte b);

    /**
     * @param c A decoded character to append
     */
    void append(char c);

    /**
     * @param bytes Array of encoded bytes to append
     */
    default void append(byte[] bytes)
    {
        append(bytes, 0, bytes.length);
    }

    /**
     * @param b Array of encoded bytes
     * @param offset offset into the array
     * @param length the number of bytes to append from the array.
     */
    default void append(byte[] b, int offset, int length)
    {
        int end = offset + length;
        for (int i = offset; i < end; i++)
            append(b[i]);
    }

    /**
     * @param chars sequence of decoded characters
     * @param offset offset into the array
     * @param length the number of character to append from the sequence.
     */
    default void append(CharSequence chars, int offset, int length)
    {
        int end = offset + length;
        for (int i = offset; i < end; i++)
            append(chars.charAt(i));
    }

    /**
     * @param buf Buffer of encoded bytes to append. The bytes are consumed from the buffer.
     */
    default void append(ByteBuffer buf)
    {
        int end = buf.position() + buf.remaining();
        while (buf.position() < end)
            append(buf.get());
    }

    /**
     * <p>Build the completed string and reset the buffer.</p>
     * @return The decoded built string which must be complete in regard to any multibyte sequences.
     * @throws CharacterCodingException If the bytes cannot be correctly decoded or a multibyte sequence is incomplete.
     */
    String build() throws CharacterCodingException;

    void reset();

    /**
     * @param charset The charset
     * @return A {@link CharsetStringBuilder} suitable for the charset.
     */
    static CharsetStringBuilder forCharset(Charset charset)
    {
        Objects.requireNonNull(charset);
        if (charset == StandardCharsets.ISO_8859_1)
            return new Iso88591StringBuilder();
        if (charset == StandardCharsets.US_ASCII)
            return new UsAsciiStringBuilder();

        // Use a CharsetDecoder that defaults to CodingErrorAction#REPORT
        return new DecoderStringBuilder(charset.newDecoder());
    }

    class Iso88591StringBuilder implements CharsetStringBuilder
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
            _builder.append(chars, offset, offset + length);
        }

        @Override
        public String build()
        {
            String s = _builder.toString();
            _builder.setLength(0);
            return s;
        }

        @Override
        public void reset()
        {
            _builder.setLength(0);
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
            _builder.append(chars, offset, offset + length);
        }

        @Override
        public String build()
        {
            String s = _builder.toString();
            _builder.setLength(0);
            return s;
        }

        @Override
        public void reset()
        {
            _builder.setLength(0);
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
                _buffer = ByteBuffer.wrap(Arrays.copyOf(_buffer.array(), _buffer.capacity() + needed - space + 32));
                _buffer.position(position);
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
                    _buffer.flip();
                    _stringBuilder.append(_decoder.decode(_buffer));
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
                    _buffer.flip();
                    _stringBuilder.append(_decoder.decode(_buffer));
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
        public String build() throws CharacterCodingException
        {
            try
            {
                if (_buffer.position() > 0)
                {
                    _buffer.flip();
                    CharSequence decoded = _decoder.decode(_buffer);
                    _buffer.clear();
                    if (_stringBuilder.length() == 0)
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

        @Override
        public void reset()
        {
            _stringBuilder.setLength(0);
        }
    }
}
