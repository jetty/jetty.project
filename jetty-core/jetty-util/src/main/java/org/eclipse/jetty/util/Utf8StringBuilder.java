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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * This class wraps a standard {@link StringBuilder} and provides methods to append
 * UTF-8 encoded bytes, that are converted into characters.
 * </p><p>
 * This class is stateful and up to 4 calls to {@link #append(byte)} may be needed before
 * state a character is appended to the string buffer.
 * </p><p>
 * The UTF-8 decoding is done by this class and no additional buffers or Readers are used.  The algorithm is
 * fast fail, in that errors are detected as the bytes are appended.  However, no exceptions are thrown and
 * only the {@link #hasCodingErrors()} method indicates the fast failure, otherwise the coding errors
 * are replaced and may be returned, unless the {@link #build()} method is used, which may throw
 * {@link CharacterCodingException}. Already decoded characters may also be appended (e.g. {@link #append(char)}
 * making this class suitable for decoding % encoded strings of already decoded characters.
 * </p>
 *
 * @see CharsetStringBuilder for decoding of arbitrary {@link java.nio.charset.Charset}s.
 */
public class Utf8StringBuilder implements CharsetStringBuilder
{
    private static final Logger LOG = LoggerFactory.getLogger(Utf8StringBuilder.class);
    public static final char REPLACEMENT = '�';
    private static final int UTF8_ACCEPT = 0;
    private static final int UTF8_REJECT = 12;

    protected int _state = UTF8_ACCEPT;

    private static final byte[] BYTE_TABLE =
        {
            // The first part of the table maps bytes to character classes that
            // to reduce the size of the transition table and create bitmasks.
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            8, 8, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            10, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 3, 3, 11, 6, 6, 6, 5, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8
        };

    private static final byte[] TRANS_TABLE =
        {
            // The second part is a transition table that maps a combination
            // of a state of the automaton and a character class to a state.
            0, 12, 24, 36, 60, 96, 84, 12, 12, 12, 48, 72, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12,
            12, 0, 12, 12, 12, 12, 12, 0, 12, 0, 12, 12, 12, 24, 12, 12, 12, 12, 12, 24, 12, 24, 12, 12,
            12, 12, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 12, 12, 24, 12, 12,
            12, 12, 12, 12, 12, 12, 12, 36, 12, 36, 12, 12, 12, 36, 12, 12, 12, 12, 12, 36, 12, 36, 12, 12,
            12, 36, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12
        };

    final StringBuilder _buffer;
    private int _codep;
    private boolean _codingErrors;

    public Utf8StringBuilder()
    {
        _buffer = new StringBuilder();
    }

    public Utf8StringBuilder(int capacity)
    {
        this(new StringBuilder(capacity));
    }

    protected Utf8StringBuilder(StringBuilder buffer)
    {
        _buffer = buffer;
    }

    @Override
    public int length()
    {
        return _buffer.length();
    }

    /**
     * @return {@code True} if the characters decoded have contained UTF8 coding errors.
     */
    public boolean hasCodingErrors()
    {
        return _codingErrors;
    }

    /**
     * Reset the appendable, clearing the buffer, resetting decoding state and clearing any errors.
     */
    @Override
    public void reset()
    {
        _state = UTF8_ACCEPT;
        _codep = 0;
        _codingErrors = false;
        bufferReset();
    }

    /**
     * Partially reset the appendable: clear the buffer and clear any errors, but retain the decoding state
     * of any partially decoded sequences.
     */
    public void partialReset()
    {
        _codingErrors = false;
        bufferReset();
    }

    protected void checkCharAppend()
    {
        if (_state != UTF8_ACCEPT)
        {
            bufferAppend(REPLACEMENT);
            _state = UTF8_ACCEPT;
            _codingErrors = true;
        }
    }

    public boolean replaceIncomplete()
    {
        if (_state == UTF8_ACCEPT)
            return false;

        bufferAppend(REPLACEMENT);
        _state = UTF8_ACCEPT;
        _codep = 0;
        _codingErrors = true;
        return true;
    }

    public void append(char c)
    {
        checkCharAppend();
        _buffer.append(c);
    }

    public void append(String s)
    {
        checkCharAppend();
        _buffer.append(s);
    }

    public void append(String s, int offset, int length)
    {
        checkCharAppend();
        _buffer.append(s, offset, offset + length);
    }

    @Override
    public void append(byte b)
    {
        try
        {
            appendByte(b);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void append(ByteBuffer buf)
    {
        try
        {
            while (buf.remaining() > 0)
            {
                appendByte(buf.get());
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void append(byte[] b)
    {
        append(b, 0, b.length);
    }

    @Override
    public void append(byte[] b, int offset, int length)
    {
        try
        {
            int end = offset + length;
            for (int i = offset; i < end; i++)
            {
                appendByte(b[i]);
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public boolean append(byte[] b, int offset, int length, int maxChars)
    {
        try
        {
            int end = offset + length;
            for (int i = offset; i < end; i++)
            {
                if (length() > maxChars)
                    return false;
                appendByte(b[i]);
            }
            return true;
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    protected void bufferAppend(char c)
    {
        _buffer.append(c);
    }

    protected void bufferReset()
    {
        _buffer.setLength(0);
    }

    public void appendByte(byte b) throws IOException
    {
        if (b > 0 && _state == UTF8_ACCEPT)
        {
            bufferAppend((char)(b & 0xFF));
        }
        else
        {
            int i = b & 0xFF;
            int type = BYTE_TABLE[i];
            _codep = _state == UTF8_ACCEPT ? (0xFF >> type) & i : (i & 0x3F) | (_codep << 6);
            int next = TRANS_TABLE[_state + type];

            if (LOG.isDebugEnabled())
            {
                LOG.debug("decode(state={}, b={}: {}) _codep={}, i={}, type={}, s={}",
                    String.format("%2d", _state),
                    String.format("0x%02X", (b & 0xFF)),
                    String.format("%8s", Integer.toBinaryString(b & 0xFF)),
                    _codep,
                    i, type, (next == UTF8_REJECT) ? "REJECT" : (next == UTF8_ACCEPT) ? "ACCEPT" : next
                );
            }

            switch (next)
            {
                case UTF8_ACCEPT ->
                {
                    if (_codep < Character.MIN_HIGH_SURROGATE)
                    {
                        bufferAppend((char)_codep);
                    }
                    else
                    {
                        for (char c : Character.toChars(_codep))
                        {
                            bufferAppend(c);
                        }
                    }
                    _codep = 0;
                    _state = next;
                }
                case UTF8_REJECT ->
                {
                    _codep = 0;
                    bufferAppend(REPLACEMENT);
                    _codingErrors = true;
                    if (_state != UTF8_ACCEPT)
                    {
                        _state = UTF8_ACCEPT;
                        appendByte(b);
                    }
                }
                default -> _state = next;
            }
        }
    }

    /**
     * @return {@code True} if the appended sequences are complete UTF-8 sequences.
     */
    public boolean isComplete()
    {
        return _state == UTF8_ACCEPT;
    }

    /**
     * Complete the appendable, adding a replacement character and coding error if the sequence is not currently complete.
     */
    public void complete()
    {
        if (!isComplete())
        {
            _codep = 0;
            _state = UTF8_ACCEPT;
            _codingErrors = true;
            bufferAppend(REPLACEMENT);
        }
    }

    /**
     * @return The currently decoded string, excluding any partial sequences appended.
     */
    @Override
    public String toString()
    {
        return "%s@%x{b=%s,s=%d,cp=%d,e=%b".formatted(
            Utf8StringBuilder.class.getSimpleName(),
            hashCode(),
            _buffer,
            _state,
            _codep,
            _codingErrors);
    }

    /**
     * @return The currently decoded string, excluding any partial sequences appended.
     */
    public String toPartialString()
    {
        return _buffer.toString();
    }

    /**
     * Get the completely decoded string, which is equivalent to calling {@link #complete()} then {@link #toString()}.
     *
     * @return The completely decoded string.
     */
    public String toCompleteString()
    {
        complete();
        return _buffer.toString();
    }

    /**
     * Take the completely decoded string.
     *
     * @param onCodingError A supplier of a {@link Throwable} to use if {@link #hasCodingErrors()} returns true,
     * or null for no error action
     * @param <X> The type of the exception thrown
     * @return The complete string.
     * @throws X if {@link #hasCodingErrors()} is true after {@link #complete()}.
     */
    public <X extends Throwable> String takeCompleteString(Supplier<X> onCodingError) throws X
    {
        complete();
        return takePartialString(onCodingError);
    }

    /**
     * Take the partially decoded string.
     *
     * @param onCodingError A supplier of a {@link Throwable} to use if {@link #hasCodingErrors()} returns true,
     * or null for no error action
     * @param <X> The type of the exception thrown
     * @return The complete string.
     * @throws X if {@link #hasCodingErrors()} is true after {@link #complete()}.
     */
    public <X extends Throwable> String takePartialString(Supplier<X> onCodingError) throws X
    {
        if (onCodingError != null && hasCodingErrors())
        {
            X x = onCodingError.get();
            if (x != null)
                throw x;
        }
        String string = _buffer.toString();
        bufferReset();
        return string;
    }

    @Override
    public String build() throws CharacterCodingException
    {
        return takeCompleteString(Utf8CharacterCodingException::new);
    }

    public static class Utf8CharacterCodingException extends CharacterCodingException
    {
        @Override
        public String getMessage()
        {
            return "Invalid UTF-8";
        }

        @Override
        public String toString()
        {
            return "%s@%x: Invalid UTF-8".formatted(CharacterCodingException.class.getSimpleName(), hashCode());
        }
    }

    public static class Utf8IllegalArgumentException extends IllegalArgumentException
    {
        public Utf8IllegalArgumentException()
        {
            super(new Utf8CharacterCodingException());
        }
    }
}
