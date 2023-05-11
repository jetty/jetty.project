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
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.StringTokenizer;

/**
 * StringTokenizer with Quoting support.
 * <p>
 * This class extends {@link StringTokenizer} with partial handling of
 * <a href="https://www.rfc-editor.org/rfc/rfc9110#name-quoted-strings">RFC9110 quoted-string</a>s.
 * The deviation from the RFC is that characters are not enforced to be
 * {@code qdtext = HTAB / SP / %x21 / %x23-5B / %x5D-7E / obs-text} and it is expected
 * that the caller will enforce any character restrictions.
 *
 * @see java.util.StringTokenizer
 */
public class QuotedStringTokenizer
{
    public static final QuotedStringTokenizer COMMA_SEPARATED_VALUES = QuotedStringTokenizer.builder().delimiters(",").optionalWhiteSpace().build();
    private static final String __delim = "\t\n\r";

    public static class Builder
    {
        private boolean _returnQuotes;
        private boolean _returnDelimiters;
        private boolean _optionalWhiteSpace;
        private boolean _embeddedQuotes;
        private String _delim = __delim;

        private Builder()
        {}

        public Builder delimiters(String delim)
        {
            _delim = delim;
            return this;
        }

        public Builder returnQuotes()
        {
            _returnQuotes = true;
            return this;
        }

        public Builder returnDelimiters()
        {
            _returnDelimiters = true;
            return this;
        }

        public Builder optionalWhiteSpace()
        {
            _optionalWhiteSpace = true;
            return this;
        }

        public Builder embeddedQuotes()
        {
            _embeddedQuotes = true;
            return this;
        }

        public QuotedStringTokenizer build()
        {
            return new QuotedStringTokenizer(_delim, _optionalWhiteSpace, _returnDelimiters, _returnQuotes, _embeddedQuotes);
        }
    }

    public static Builder builder()
    {
        return new Builder();
    }

    private final String _delim;
    private final boolean _optionalWhiteSpace;
    private final boolean _returnDelimiters;
    private final boolean _returnQuotes;
    private final boolean _embeddedQuotes;

    private QuotedStringTokenizer(String delim,
                                 boolean optionalWhiteSpace,
                                 boolean returnDelimiters,
                                 boolean returnQuotes,
                                 boolean embeddedQuotes)
    {
        _delim = delim == null ? __delim : delim;
        _optionalWhiteSpace = optionalWhiteSpace;
        _returnDelimiters = returnDelimiters;
        _returnQuotes = returnQuotes;
        _embeddedQuotes = embeddedQuotes;

        if (_delim.indexOf('"') >= 0)
            throw new IllegalArgumentException("Can't use quote as delimiters: " + _delim);
        if (_optionalWhiteSpace && _delim.indexOf(' ') >= 0)
            throw new IllegalArgumentException("Can't delimit with space with optional white space");
    }

    protected boolean isOWS(char c)
    {
        return c == ' ' || c == '\t';
    }

    public Iterator<String> tokenize(String string)
    {
        Objects.requireNonNull(string);

        return new Iterator<>()
        {
            private enum State
            {
                START,
                TOKEN,
                QUOTE,
                END,
            }

            private final StringBuilder _token = new StringBuilder();
            State _state = State.START;
            private boolean _hasToken;
            private int _ows = -1;
            private int _i = 0;

            @Override
            public boolean hasNext()
            {
                if (_hasToken)
                    return true;

                boolean escape = false;
                while (_i < string.length())
                {
                    char c = string.charAt(_i++);

                    switch (_state)
                    {
                        case START ->
                        {
                            if (_delim.indexOf(c) >= 0)
                            {
                                if (_returnDelimiters)
                                {
                                    _token.append(c);
                                    return _hasToken = true;
                                }
                            }
                            else if (c == '"')
                            {
                                if (_returnQuotes)
                                    _token.append(c);
                                _ows = -1;
                                _state = State.QUOTE;
                            }
                            else if (!_optionalWhiteSpace || !isOWS(c))
                            {
                                _token.append(c);
                                _hasToken = true;
                                _ows = -1;
                                _state = State.TOKEN;
                            }
                        }
                        case TOKEN ->
                        {
                            _hasToken = true;
                            if (_delim.indexOf(c) >= 0)
                            {
                                if (_returnDelimiters)
                                    _i--;
                                _state = State.START;
                                if (_ows >= 0)
                                    _token.setLength(_ows);
                                return _hasToken;
                            }
                            else if (_embeddedQuotes && c == '"')
                            {
                                if (_returnQuotes)
                                    _token.append(c);
                                _ows = -1;
                                _state = State.QUOTE;
                            }
                            else if (_optionalWhiteSpace && isOWS(c))
                            {
                                if (_ows < 0)
                                    _ows = _token.length();
                                _token.append(c);
                            }
                            else
                            {
                                _ows = -1;
                                _token.append(c);
                            }
                        }
                        case QUOTE ->
                        {
                            _hasToken = true;
                            if (escape)
                            {
                                escape = false;
                                _token.append(c);
                            }
                            else if (c == '\"')
                            {
                                if (_returnQuotes)
                                    _token.append(c);
                                if (_embeddedQuotes)
                                {
                                    _ows = -1;
                                    _state = State.TOKEN;
                                }
                                else
                                {
                                    _state = State.END;
                                    return _hasToken;
                                }
                            }
                            else if (c == '\\')
                            {
                                if (_returnQuotes)
                                    _token.append(c);
                                escape = true;
                            }
                            else
                            {
                                _token.append(c);
                            }
                        }
                        case END ->
                        {
                            if (_delim.indexOf(c) >= 0)
                            {
                                _state = State.START;
                                if (_returnDelimiters)
                                {
                                    _token.append(c);
                                    return _hasToken = true;
                                }
                            }
                            else if (!_optionalWhiteSpace || !isOWS(c))
                                throw new IllegalArgumentException("characters after end quote");
                        }
                        default -> throw new IllegalStateException();
                    }
                }

                if (_ows >= 0 && _hasToken)
                    _token.setLength(_ows);

                return _hasToken;
            }

            @Override
            public String next()
            {
                if (!hasNext())
                    throw new NoSuchElementException();
                String t = _token.toString();
                _token.setLength(0);
                _hasToken = false;
                return t;
            }
        };
    }

    /**
     * Quote a string.
     * The string is quoted only if quoting is required due to
     * embedded delimiters, quote characters or the empty string.
     *
     * @param s The string to quote.
     * @return quoted string
     */
    public String quoteIfNeeded(String s)
    {
        if (s == null)
            return null;
        if (s.length() == 0)
            return "\"\"";

        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '\\' || c == '"' || _delim.indexOf(c) >= 0)
            {
                StringBuffer b = new StringBuffer(s.length() + 8);
                quote(b, s);
                return b.toString();
            }
        }

        return s;
    }

    /**
     * Append into buf the provided string, adding quotes if needed.
     * <p>
     * Quoting is determined if any of the characters in the {@code delim} are found in the input {@code str}.
     *
     * @param buf the buffer to append to
     * @param str the string to possibly quote
     */
    public void quoteIfNeeded(StringBuilder buf, String str)
    {
        if (str == null)
            return;
        if (str.length() == 0)
        {
            buf.append("\"\"");
            return;
        }

        for (int i = 0; i < str.length(); i++)
        {
            char c = str.charAt(i);
            if (c == '\\' ||
                c == '"' ||
                _delim.indexOf(c) >= 0 ||
                _optionalWhiteSpace && c == ' '
            )
            {
                quote(buf, str);
                return;
            }
        }

        // no special delimiters used, no quote needed.
        buf.append(str);
    }

    /**
     * Quote a string.
     * The string is quoted only if quoting is required due to
     * embedded delimiters, quote characters or the
     * empty string.
     *
     * @param s The string to quote.
     * @return quoted string
     */
    public static String quote(String s)
    {
        if (s == null)
            return null;
        if (s.length() == 0)
            return "\"\"";

        StringBuffer b = new StringBuffer(s.length() + 8);
        quote(b, s);
        return b.toString();
    }

    /**
     * Quote a string into an Appendable.
     *
     * @param buffer The Appendable
     * @param input The String to quote.
     */
    public static void quote(Appendable buffer, String input)
    {
        if (input == null)
            return;

        try
        {
            buffer.append('"');
            for (int i = 0; i < input.length(); ++i)
            {
                char c = input.charAt(i);
                if (c == '"' || c == '\\')
                    buffer.append('\\').append(c);
                else
                    buffer.append(c);
            }
            buffer.append('"');
        }
        catch (IOException x)
        {
            throw new RuntimeException(x);
        }
    }

    /**
     * Unquote a string.
     *
     * @param s The string to unquote.
     * @return unquoted string
     */
    public static String unquote(String s)
    {
        if (s == null)
            return null;
        if (s.length() < 2)
            return s;

        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if (first != '"' || last != '"')
            return s;

        StringBuilder b = new StringBuilder(s.length() - 2);
        boolean escape = false;
        for (int i = 1; i < s.length() - 1; i++)
        {
            char c = s.charAt(i);

            if (escape)
            {
                escape = false;
                b.append(c);
            }
            else if (c == '\\')
            {
                escape = true;
            }
            else
            {
                b.append(c);
            }
        }

        return b.toString();
    }

    public static boolean isQuoted(String s)
    {
        return s != null && s.length() > 0 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"';
    }
}
