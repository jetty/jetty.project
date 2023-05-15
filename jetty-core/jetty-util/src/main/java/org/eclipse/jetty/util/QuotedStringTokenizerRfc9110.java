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
public class QuotedStringTokenizerRfc9110 implements QuotedStringTokenizer
{
    private final String _delim;
    private final boolean _optionalWhiteSpace;
    private final boolean _returnDelimiters;
    private final boolean _returnQuotes;
    private final boolean _embeddedQuotes;
    private final boolean _escapeOnlyQuote;

    QuotedStringTokenizerRfc9110(String delim,
                                 boolean optionalWhiteSpace,
                                 boolean returnDelimiters,
                                 boolean returnQuotes,
                                 boolean embeddedQuotes,
                                 boolean escapeOnlyQuote)
    {
        _delim = Objects.requireNonNull(delim);
        _optionalWhiteSpace = optionalWhiteSpace;
        _returnDelimiters = returnDelimiters;
        _returnQuotes = returnQuotes;
        _embeddedQuotes = embeddedQuotes;
        _escapeOnlyQuote = escapeOnlyQuote;

        if (_delim.indexOf('"') >= 0)
            throw new IllegalArgumentException("Can't use quote as delimiters: " + _delim);
        if (_optionalWhiteSpace && _delim.indexOf(' ') >= 0)
            throw new IllegalArgumentException("Can't delimit with space with optional white space");
    }

    protected boolean isOWS(char c)
    {
        return Character.isWhitespace(c);
    }

    @Override
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
                                if (_escapeOnlyQuote && (_i >= string.length() || string.charAt(_i) != '"'))
                                    _token.append(c);
                                else
                                {
                                    if (_returnQuotes)
                                        _token.append(c);
                                    escape = true;
                                }
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

                if (_state == State.QUOTE)
                    throw new IllegalArgumentException("unterminated quote");

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
     * Quote a string into an Appendable.
     *
     * @param buffer The Appendable
     * @param input The String to quote.
     */
    @Override
    public void quote(Appendable buffer, String input)
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

    @Override
    public String quoteIfNeeded(String s)
    {
        return quoteIfNeededImpl(null, s);
    }

    @Override
    public void quoteIfNeeded(StringBuilder buf, String str)
    {
        quoteIfNeededImpl(buf, str);
    }

    @Override
    public boolean needsQuoting(char c)
    {
        return c == '\\' || c == '"' || _optionalWhiteSpace && Character.isWhitespace(c) || _delim.indexOf(c) >= 0;
    }

    private String quoteIfNeededImpl(StringBuilder buf, String str)
    {
        if (str == null)
            return null;
        if (str.length() == 0)
        {
            if (buf == null)
                return "\"\"";

            buf.append("\"\"");
            return null;
        }

        for (int i = 0; i < str.length(); i++)
        {
            char c = str.charAt(i);
            if (needsQuoting(c))
            {
                if (buf == null)
                    return quote(str);
                quote(buf, str);
                return null;
            }
        }

        // no special delimiters used, no quote needed.
        if (buf == null)
            return str;
        buf.append(str);
        return null;
    }

    /**
     * Unquote a string.
     *
     * @param s The string to unquote.
     * @return unquoted string
     */
    public String unquote(String s)
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

    @Override
    public String toString()
    {
        StringBuilder out = new StringBuilder();
        out.append(getClass().getSimpleName()).append('@').append(Long.toHexString(hashCode()))
            .append("{'").append(_delim).append('\'');

        if (_optionalWhiteSpace)
            out.append(",optionalWhiteSpace");
        if (_returnDelimiters)
            out.append(",returnDelimiters");
        if (_returnQuotes)
            out.append(",returnQuotes");
        if (_embeddedQuotes)
            out.append(",embeddedQuotes");
        if (_escapeOnlyQuote)
            out.append(",escapeOnlyQuote");
        out.append('}');
        return out.toString();
    }
}
