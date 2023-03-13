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
import java.nio.charset.CodingErrorAction;

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

    private final CodingErrorAction _coderErrorAction;
    private int _codep;
    private boolean _hasReplacements = false;

    public Utf8Appendable(Appendable appendable)
    {
        this(appendable, CodingErrorAction.REPORT);
    }

    public Utf8Appendable(Appendable appendable, CodingErrorAction codingErrorAction)
    {
        _appendable = appendable;
        _coderErrorAction = codingErrorAction;
    }

    public abstract int length();

    protected void reset()
    {
        _codep = 0;
        _state = UTF8_ACCEPT;
    }

    private void checkCharAppend() throws IOException
    {
        if (_state != UTF8_ACCEPT)
        {
            if (_coderErrorAction == CodingErrorAction.REPLACE)
            {
                _appendable.append(REPLACEMENT);
                _hasReplacements = true;
            }
            int state = _state;
            _state = UTF8_ACCEPT;
            if (_coderErrorAction == CodingErrorAction.REPORT)
                throw new NotUtf8Exception("char appended in state " + state);
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
                case UTF8_ACCEPT:
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
                    break;

                case UTF8_REJECT:
                    if (_coderErrorAction == CodingErrorAction.REPORT)
                    {
                        final String reason = "byte " + TypeUtil.toHexString(b) + " in state " + (_state / 12);
                        _codep = 0;
                        _state = current;
                        throw new NotUtf8Exception(reason);
                    }
                    else if (_coderErrorAction == CodingErrorAction.REPLACE)
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
                    break;
                default:
                    _state = current;
            }
        }
    }

    public boolean isUtf8SequenceComplete()
    {
        return _state == UTF8_ACCEPT;
    }

    /**
     * Not a valid sequences of UTF-8 bytes
     */
    public static class NotUtf8Exception extends IllegalArgumentException
    {
        public NotUtf8Exception(String reason)
        {
            super("Not valid UTF8! " + reason);
        }
    }

    /**
     * Check the state of the Appendable to know if the current UTF-8 sequence is complete (or not)
     */
    public void checkState()
    {
        if (!isUtf8SequenceComplete())
        {
            if (_coderErrorAction == CodingErrorAction.REPLACE)
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
            if (_coderErrorAction == CodingErrorAction.REPORT)
                throw new NotUtf8Exception("incomplete UTF8 sequence");
        }
    }

    /**
     * @return The UTF8 so far decoded, ignoring partial code points
     */
    public abstract String getPartialString();

    /**
     * Take the partial string a reset in internal buffer, but retain
     * partial code points.
     *
     * @return The UTF8 so far decoded, ignoring partial code points
     */
    public String takePartialString()
    {
        String partial = getPartialString();
        int savedState = _state;
        int savedCodepoint = _codep;
        reset();
        _state = savedState;
        _codep = savedCodepoint;
        return partial;
    }

    /**
     * @return the String from the appendable, with checks on final valid utf-8 byte sequence
     */
    public String toReplacedString()
    {
        if (!isUtf8SequenceComplete())
        {
            _codep = 0;
            _state = UTF8_ACCEPT;
            if (_coderErrorAction == CodingErrorAction.REPORT)
            {
                Throwable th = new NotUtf8Exception("incomplete UTF8 sequence");
                if (LOG.isDebugEnabled())
                    LOG.warn("Unable to get replacement string", th);
                else
                    LOG.warn("Unable to get replacement string {}", th.toString());
            }

        }
        return _appendable.toString();
    }
}
