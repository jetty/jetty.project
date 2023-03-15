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
    // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
    public static final char REPLACEMENT = '\ufffd';
    // @checkstyle-enable-check : AvoidEscapedUnicodeCharactersCheck
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
    private boolean _hasReplacements = false;

    /**
     * Construct with {@link Appendable}
     * @param appendable the appendable to put encoded characters into
     */
    public Utf8Appendable(Appendable appendable)
    {
        _appendable = appendable;
    }

    /**
     * The length of the {@link Appendable} buffer
     * @return the length of the {@link Appendable} buffer in count of codepoints.
     */
    public abstract int length();

    /**
     * Reset the internal state of this {@code Utf8Appendable} and reset out the {@link Appendable} buffers.
     */
    public void reset()
    {
        _codep = 0;
        _state = UTF8_ACCEPT;
        _hasReplacements = false;
        clear();
    }

    private void checkCharAppend() throws IOException
    {
        if (_state != UTF8_ACCEPT)
        {
            int state = _state;
            _state = UTF8_ACCEPT;
            _appendable.append(REPLACEMENT);
            _hasReplacements = true;
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

    private int decode(int state, byte b)
    {
        int i = b & 0xFF;
        int type = BYTE_TABLE[i];
        _codep = _state == UTF8_ACCEPT ? (0xFF >> type) & i : (i & 0x3F) | (_codep << 6);
        int s = TRANS_TABLE[_state + type];

        if (LOG.isDebugEnabled())
        {
            LOG.info("decode(state={}, b={}: {}) _codep={}, i={}, type={}, s={}",
                String.format("%2d", state),
                String.format("0x%02X", (b & 0xFF)),
                String.format("%8s", Integer.toBinaryString(b & 0xFF)),
                _codep,
                i, type, (s == UTF8_REJECT) ? "REJECT" : (s == UTF8_ACCEPT) ? "ACCEPT" : s
            );
        }

        return s;
    }

    /**
     * Append a byte to the buffer, taking care to form up UTF-8 codepoints.
     *
     * <p>
     *     Invalid UTF-8 sequences will result in a Replacement Character.
     * </p>
     *
     * @param b the byte to add
     * @throws IOException if unable to add result to underlying {@link Appendable}
     */
    public void appendByte(byte b) throws IOException
    {
        if (false && b > 0 && _state == UTF8_ACCEPT)
        {
            // single byte append
            _appendable.append((char)(b & 0xFF));
        }
        else
        {
            int current = decode(_state, b);
            switch (current)
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
                    _codep = 0; // reset codepoint, as we've written it now
                    _state = current;
                }

                case UTF8_REJECT ->
                {
                    _appendable.append(REPLACEMENT);
                    _hasReplacements = true;
                    _codep = 0; // it's a bad codepoint, don't use it

                    if (_state != UTF8_ACCEPT)
                    {
                        _state = UTF8_ACCEPT;
                        appendByte(b);
                    }
                }
                default ->
                {
                    _state = current;
                }
            }
        }
    }

    public boolean isUtf8SequenceComplete()
    {
        return _state == UTF8_ACCEPT;
    }

    /**
     * Finish the buffer state.
     * <p>
     *     This will address any incomplete utf-8 sequences in the buffer, and set Utf8Appendable in a state
     *     where it will be ready to accept new utf-8 sequences.
     * </p>
     */
    public void finish()
    {
        if (!isUtf8SequenceComplete())
        {
            try
            {
                _appendable.append(REPLACEMENT);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
            _hasReplacements = true;
        }
        _codep = 0;
        _state = UTF8_ACCEPT;
    }

    /**
     * Clear out the {@link Appendable} of any content, resetting it to zero.
     * <p>
     *     This will not clear out any partial code points.
     * </p>
     */
    public abstract void clear();

    /**
     * True if there was a replacement of a bad UTF-8 sequence with a UTF-8 replacement character.
     *
     * <p>
     *     If the input already has a replacement character, this is not counted when using
     *     this method.
     * </p>
     *
     * @return true if there was a replacement of an appended character or byte with a replacement character.
     */
    public boolean hasReplacements()
    {
        return _hasReplacements;
    }

    /**
     * Get the String.
     *
     * @param completeCodePoints true to complete any active codepoints/sequences before returning the String
     * @param throwableOnReplacementsSupplier if a replacement of a character occurred during append,
     * and if this supplier is non-null, then use this supplier to throw an error
     * @return the String
     * @throws X if replacement of bad characters occurred, and {@code throwableOnReplacementsSupplier} was provided
     */
    public <X extends Throwable> String getString(boolean completeCodePoints, Supplier<? extends X> throwableOnReplacementsSupplier) throws X
    {
        if (throwableOnReplacementsSupplier != null && hasReplacements())
        {
            X error = throwableOnReplacementsSupplier.get();
            throw error;
        }
        return _appendable.toString();
    }

    public String getString(boolean completeCodePoints)
    {
        return getString(completeCodePoints, null);
    }

    /**
     * Get the String as it exists currently.
     *
     * <p>
     *     This will get the String as it exists currently, without including any trailing incomplete UTF-8 sequences.
     * </p>
     * <p>
     *     Use {@link #finish()} to complete any incomplete UTF-8 sequences before calling this method.
     * </p>
     *
     * @return the String as it exists currently, without throwing an Exception
     * @see #finish()
     */
    public String getString()
    {
        return getString(true, null);
    }

    /**
     * <p>
     * Take the String from the {@link Appendable}, taking care to finish any incomplete UTF-8 sequences first.
     * </p>
     *
     * <p>
     * Calls to this method will {@link #reset()} this {@link Utf8Appendable}.
     * </p>
     *
     * @return the String buffer from the appendable
     * @throws CharacterCodingException if unable to encode the input bytes (eg: bad UTF-8 sequences)
     */
    @Override
    public String takeString() throws CharacterCodingException
    {
        try
        {
            return getString(true, () ->
            {
                CharacterCodingException characterCodingException = new CharacterCodingException();
                characterCodingException.initCause(new NotUtf8Exception("Not valid UTF-8"));
                return characterCodingException;
            });
        }
        finally
        {
            reset();
        }
    }

    /**
     * Default toString implementation
     * @return String representation of this object
     */
    public String toString()
    {
        // NOTE: Do not trigger state change in this method!
        // Don't call reset(), or clear(), or change things like the state or codep!
        // This breaks behavior when debugging or logging.
        return String.format("%s@%h[%s]", this.getClass().getName(), hashCode(), _appendable.toString());
    }

    /**
     * Not a valid sequences of UTF-8 bytes
     */
    public static class NotUtf8Exception extends IllegalArgumentException
    {
        public NotUtf8Exception(String reason)
        {
            super(reason);
        }
    }

    public static final Supplier<NotUtf8Exception> NOT_UTF8 = () -> new NotUtf8Exception("Not valid UTF8");
}
