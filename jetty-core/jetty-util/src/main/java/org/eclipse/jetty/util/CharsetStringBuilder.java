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
import java.util.Objects;

/**
 * <p>Build a string from a sequence of bytes and/or characters.</p>
 * <p>Implementations of this interface are optimized for processing a mix of calls to already decoded
 * character based appends (e.g. {@link #append(char)}) and calls to undecoded byte methods (e.g. {@link #append(byte)}).
 * This is particularly useful for decoding % encoded strings that are mostly already decoded but may contain
 * escaped byte sequences that are not decoded.  The standard {@link CharsetDecoder} API is not well suited for this
 * use-case.</p>
 * <p>Any coding errors in the string will be reported by a {@link CharacterCodingException} thrown
 * from the {@link #build()} method.</p>
 * @see Utf8StringBuilder for UTF-8 decoding with replacement of coding errors and/or fast fail behaviour.
 * @see CharsetDecoder for decoding arbitrary {@code Charset}s with control over {@code CodingErrorAction}.
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
    default String build() throws CharacterCodingException
    {
        return build(false);
    }

    /**
     * <p>Attempt to build the completed string and reset the buffer,
     * returning a partial string if there are encoding errors</p>
     *
     * <p>Note, only some implementations support the {@code allowPartialString}
     * parameter</p>
     *
     * @param allowPartialString true if a partial string is allowed to be returned,
     * false means if complete string cannot be returned, an exception is thrown.
     * @return The available string (complete or partial)
     * @throws CharacterCodingException (only if {@code allowPartialString} is false) thrown if the bytes cannot be correctly decoded or a multibyte sequence is incomplete.
     */
    String build(boolean allowPartialString) throws CharacterCodingException;

    /**
     * <p>Test for if there are and detected encoding errors</p>
     *
     * @return {@code True} if the characters in the builder contain encoding errors.
     *   Such as bad sequences, incomplete sequences, replacement characters, etc.
     */
    default boolean hasCodingErrors()
    {
        return false;
    }

    /**
     * <p>If there is an incomplete sequence, replace it with the encoding
     * specific replacement character.</p>
     *
     * <p>Will set the encoding errors to true for {@link #hasCodingErrors()}</p>
     *
     * @return true if replacement occurred, false if there was no issue.
     */
    default boolean replaceIncomplete()
    {
        return false;
    }

    /**
     * @return the length in characters
     */
    int length();

    /**
     * <p>Resets this sequence to be empty.</p>
     */
    void reset();

    /**
     * @param charset The charset
     * @return A {@link CharsetStringBuilder} suitable for the charset.
     */
    static CharsetStringBuilder forCharset(Charset charset)
    {
        return forCharset(charset, CodingErrorAction.REPORT, CodingErrorAction.REPORT);
    }

    static CharsetStringBuilder forCharset(Charset charset, CodingErrorAction onMalformedInput, CodingErrorAction onUnmappableCharacter)
    {
        Objects.requireNonNull(charset);
        if (charset == StandardCharsets.UTF_8)
            return new Utf8StringBuilder(onMalformedInput, onUnmappableCharacter);
        if (charset == StandardCharsets.ISO_8859_1)
            return new Iso88591StringBuilder();
        if (charset == StandardCharsets.US_ASCII)
            return new UsAsciiStringBuilder();

        // Use a CharsetDecoder that defaults to CodingErrorAction#REPORT
        return new DecoderStringBuilder(charset.newDecoder(), onMalformedInput, onUnmappableCharacter);
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
        public String build(boolean ignored)
        {
            return build();
        }

        @Override
        public int length()
        {
            return _builder.length();
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
        public String build(boolean ignored)
        {
            return build();
        }

        @Override
        public int length()
        {
            return _builder.length();
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
        private ByteBuffer _buffer;
        
        public DecoderStringBuilder(CharsetDecoder charsetDecoder, CodingErrorAction onMalformedInput, CodingErrorAction onUnmappableCharacter)
        {
            _decoder = charsetDecoder;
            _decoder.onMalformedInput(onMalformedInput);
            _decoder.onUnmappableCharacter(onUnmappableCharacter);
        }

        private void ensureSpace(int needed)
        {
            if (_buffer == null)
            {
                _buffer = ByteBuffer.allocate(needed + 32);
            }
            else
            {
                int space = _buffer.remaining();
                if (space < needed)
                {
                    int position = _buffer.position();
                    _buffer = ByteBuffer.wrap(ArrayUtil.grow(_buffer.array(), needed, Integer.MAX_VALUE)).position(position);
                }
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
            if (_buffer != null && _buffer.position() > 0)
            {
                // If we have seen a byte append, then assume all following character appends are mistakes,
                // but try to decode safely if we can.
                if (c > 0xFF)
                {
                    // Try to decode and continue
                    try
                    {
                        CharSequence decoded = _decoder.decode(_buffer.flip());
                        _buffer.clear();
                        _stringBuilder.append(decoded);
                        _stringBuilder.append(c);
                    }
                    catch (CharacterCodingException e)
                    {
                        if (_decoder.malformedInputAction() == CodingErrorAction.IGNORE)
                            return;
                        if (_decoder.malformedInputAction() == CodingErrorAction.REPLACE)
                        {
                            _buffer.clear();
                            _stringBuilder.append(_decoder.replacement());
                            return;
                        }
                        throw new IllegalArgumentException("Invalid character " + Integer.toHexString(c), e);
                    }
                }
                else
                {
                    // This only works for charsets that are true supersets of USASCII
                    ensureSpace(1);
                    _buffer.put((byte)c);
                }
            }
            else
            {
                _stringBuilder.append(c);
            }
        }

        @Override
        public void append(CharSequence chars, int offset, int length)
        {
            for (int idx = offset; idx < offset + length; idx++)
                append(chars.charAt(idx));
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
        public String build(boolean ignore) throws CharacterCodingException
        {
            // the parameter is ignored, as the CharsetDecoder configuration
            // determines the behavior.
            // See onMalformedInput(CodingErrorAction)
            // and onUnmappableCharacter(CodingErrorAction)
            try
            {
                if (_buffer != null && _buffer.position() > 0)
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

        @Override
        public int length()
        {
            return _stringBuilder.length();
        }

        @Override
        public void reset()
        {
            _stringBuilder.setLength(0);
            if (_buffer != null)
                _buffer.clear();
        }
    }
}


