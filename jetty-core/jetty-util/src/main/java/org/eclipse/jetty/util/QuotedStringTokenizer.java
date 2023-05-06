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
import java.util.NoSuchElementException;
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
public class QuotedStringTokenizer extends StringTokenizer
{
    private static final String __delim = "\t\n\r";
    private final String _string;
    private final StringBuffer _token;
    private final boolean _returnQuotes;
    private final boolean _returnDelimiters;
    private String _delim = __delim;
    private boolean _hasToken = false;
    private int _i = 0;
    private int _lastStart = 0;

    public QuotedStringTokenizer(String str,
                                 String delim,
                                 boolean returnDelimiters,
                                 boolean returnQuotes)
    {
        super("");
        _string = str;
        if (delim != null)
            _delim = delim;
        _returnDelimiters = returnDelimiters;
        _returnQuotes = returnQuotes;

        if (_delim.indexOf('"') >= 0)
            throw new Error("Can't use quotes as delimiters: " + _delim);

        _token = new StringBuffer(_string.length() > 1024 ? 512 : _string.length() / 2);
    }

    public QuotedStringTokenizer(String str,
                                 String delim,
                                 boolean returnDelimiters)
    {
        this(str, delim, returnDelimiters, false);
    }

    public QuotedStringTokenizer(String str,
                                 String delim)
    {
        this(str, delim, false, false);
    }

    public QuotedStringTokenizer(String str)
    {
        this(str, null, false, false);
    }

    @Override
    public boolean hasMoreTokens()
    {
        // Already found a token
        if (_hasToken)
            return true;

        _lastStart = _i;

        int state = 0;
        boolean escape = false;
        while (_i < _string.length())
        {
            char c = _string.charAt(_i++);

            switch (state)
            {
                case 0 -> // Start
                {
                    if (_delim.indexOf(c) >= 0)
                    {
                        if (_returnDelimiters)
                        {
                            _token.append(c);
                            return _hasToken = true;
                        }
                    }
                    else if (c == '\"')
                    {
                        if (_returnQuotes)
                            _token.append(c);
                        state = 3;
                    }
                    else
                    {
                        _token.append(c);
                        _hasToken = true;
                        state = 1;
                    }
                }
                case 1 -> // Token
                {
                    _hasToken = true;
                    if (_delim.indexOf(c) >= 0)
                    {
                        if (_returnDelimiters)
                            _i--;
                        return _hasToken;
                    }
                    else if (c == '\"')
                    {
                        if (_returnQuotes)
                            _token.append(c);
                        state = 3;
                    }
                    else
                    {
                        _token.append(c);
                    }
                }
                case 3 -> // Double Quote
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
                        state = 1;
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
                default -> throw new IllegalStateException();
            }
        }

        return _hasToken;
    }

    @Override
    public String nextToken()
        throws NoSuchElementException
    {
        if (!hasMoreTokens() || _token == null)
            throw new NoSuchElementException();
        String t = _token.toString();
        _token.setLength(0);
        _hasToken = false;
        return t;
    }

    @Override
    public String nextToken(String delim)
        throws NoSuchElementException
    {
        _delim = delim;
        _i = _lastStart;
        _token.setLength(0);
        _hasToken = false;
        return nextToken();
    }

    @Override
    public boolean hasMoreElements()
    {
        return hasMoreTokens();
    }

    @Override
    public Object nextElement()
        throws NoSuchElementException
    {
        return nextToken();
    }

    /**
     * Not implemented.
     */
    @Override
    public int countTokens()
    {
        return -1;
    }

    /**
     * Quote a string.
     * The string is quoted only if quoting is required due to
     * embedded delimiters, quote characters or the empty string.
     *
     * @param s The string to quote.
     * @param delim the delimiter to use to quote the string
     * @return quoted string
     */
    public static String quoteIfNeeded(String s, String delim)
    {
        if (s == null)
            return null;
        if (s.length() == 0)
            return "\"\"";

        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '\\' || c == '"' || delim.indexOf(c) >= 0)
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
     * @param delim the delimiter characters that will trigger automatic quoting
     */
    public static void quoteIfNeeded(StringBuilder buf, String str, String delim)
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
            if (c == '\\' || c == '"' || delim.indexOf(c) >= 0)
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
     * Quote a string into an Appendable.
     * Only quotes and backslash are escaped.
     *
     * @param buffer The Appendable
     * @param input The String to quote.
     */
    public static void quoteOnly(Appendable buffer, String input)
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
                    buffer.append('\\');
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
     * Unquote a string, NOT converting unicode sequences
     *
     * @param s The string to unquote.
     * @return quoted string
     */
    public static String unquoteOnly(String s)
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
