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
 * Utf8 Appendable abstract base class
 * </p>
 *
 * <p>
 * This abstract class wraps a standard {@link java.lang.Appendable} and provides methods to append UTF-8 encoded bytes, that are converted into characters.
 * </p>
 *
 * <p>
 * This class is stateful and up to 4 calls to {@link #append(byte)} may be needed before state a character is appended to the string buffer.
 * </p>
 *
 * <p>
 * The UTF-8 decoding is done by this class and no additional buffers or Readers are used. The UTF-8 code was inspired by
 * <a href ="http://bjoern.hoehrmann.de/utf-8/decoder/dfa/">http://bjoern.hoehrmann.de/utf-8/decoder/dfa/</a>
 * </p>
 *
 * <p>
 * License information for Bjoern Hoehrmann's code:
 * </p>
 *
 * <p>
 * Copyright (c) 2008-2009 Bjoern Hoehrmann &lt;bjoern@hoehrmann.de&gt;<br/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * </p>
 *
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 * </p>
 *
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 * </p>
 **/
public abstract class Utf8Appendable implements CharsetStringBuilder
{
    protected static final Logger LOG = LoggerFactory.getLogger(Utf8Appendable.class);
    public static final char REPLACEMENT = '�';
    private static final int UTF8_ACCEPT = 0;
    private static final int UTF8_REJECT = 12;

    protected final Appendable _appendable;
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

    private int _codep;
    private boolean _codingErrors;

    public Utf8Appendable(Appendable appendable)
    {
        _appendable = appendable;
    }

    public abstract int length();

    /**
     * Reset the appendable pass in {@link Utf8Appendable#Utf8Appendable(Appendable)}
     */
    protected abstract void resetAppendable();

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
    public void reset()
    {
        _state = UTF8_ACCEPT;
        _codep = 0;
        _codingErrors = false;
        resetAppendable();
    }

    /**
     * Partially reset the appendable: clear the buffer and clear any errors, but retain the decoding state
     * of any partially decoded sequences.
     */
    public void partialReset()
    {
        _codingErrors = false;
        resetAppendable();
    }

    private void checkCharAppend() throws IOException
    {
        if (_state != UTF8_ACCEPT)
        {
            _appendable.append(REPLACEMENT);
            _state = UTF8_ACCEPT;
            _codingErrors = true;
        }
    }

    public void append(char c)
    {
        try
        {
            checkCharAppend();
            _appendable.append(c);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void append(String s)
    {
        try
        {
            checkCharAppend();
            _appendable.append(s);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void append(String s, int offset, int length)
    {
        try
        {
            checkCharAppend();
            _appendable.append(s, offset, offset + length);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
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

    public void appendByte(byte b) throws IOException
    {
        if (b > 0 && _state == UTF8_ACCEPT)
        {
            _appendable.append((char)(b & 0xFF));
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
                        _appendable.append((char)_codep);
                    }
                    else
                    {
                        for (char c : Character.toChars(_codep))
                        {
                            _appendable.append(c);
                        }
                    }
                    _codep = 0;
                    _state = next;
                }
                case UTF8_REJECT ->
                {
                    _codep = 0;
                    _appendable.append(REPLACEMENT);
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
            try
            {
                _appendable.append(REPLACEMENT);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * @return The currently decoded string, excluding any partial sequences appended.
     */
    @Override
    public String toString()
    {
        return _appendable.toString();
    }

    /**
     * Get the completely decoded string, which is equivalent to calling {@link #complete()} then {@link #toString()}.
     * @return The completely decoded string.
     */
    public String toCompleteString()
    {
        complete();
        return _appendable.toString();
    }

    /**
     * Take the completely decoded string, which is equivalent to calling {@link #complete()} then {@link #toString()}
     * then {@link #reset()}.
     * @param onCodingError A supplier of a {@link Throwable} to use if {@link #hasCodingErrors()} returns true,
     *                      or null for no error action
     * @param <X> The type of the exception thrown
     * @return The complete string.
     * @throws X if {@link #hasCodingErrors()} is true after {@link #complete()}.
     */
    public <X extends Throwable> String takeString(Supplier<X> onCodingError) throws X
    {
        complete();
        if (onCodingError != null && hasCodingErrors())
            throw onCodingError.get();
        String string = _appendable.toString();
        reset();
        return string;
    }

    /**
     * Take the completely decoded string, which is equivalent to calling {@link #complete()} then {@link #toString()}
     * then {@link #reset()}.
     * @return The complete string
     * @throws CharacterCodingException if {@link #hasCodingErrors()} is true after {@link #complete()}.
     */
    @Override
    public String takeString() throws CharacterCodingException
    {
        return takeString(Utf8Appendable::badUTF8);
    }

    private static CharacterCodingException badUTF8()
    {
        return new CharacterCodingException()
        {
            {
                initCause(new IllegalArgumentException("Bad UTF-8 encoding"));
            }
        };
    }
}
