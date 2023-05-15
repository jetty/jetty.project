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
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;

public class QuotedStringTokenizerLegacy implements QuotedStringTokenizer
{
    private final String _delim;
    private final boolean _returnQuotes;
    private final boolean _returnDelimiters;
    private final boolean _singleQuotes;

    QuotedStringTokenizerLegacy(String delim,
                                boolean returnDelimiters,
                                boolean returnQuotes,
                                boolean singleQuotes)
    {
        _delim = delim;
        _returnDelimiters = returnDelimiters;
        _returnQuotes = returnQuotes;
        _singleQuotes = singleQuotes;
    }

    @Override
    public Iterator<String> tokenize(String string)
    {
        LegacyTokenizer tokenizer = new LegacyTokenizer(string);
        return new Iterator<>()
        {
            @Override
            public boolean hasNext()
            {
                return tokenizer.hasMoreTokens();
            }

            @Override
            public String next()
            {
                return tokenizer.nextToken();
            }
        };
    }

    @Override
    public boolean needsQuoting(char c)
    {
        return LegacyTokenizer.needsQuoting(c, _delim);
    }

    @Override
    public String quoteIfNeeded(String s)
    {
        return LegacyTokenizer.quoteIfNeeded(s, _delim);
    }

    @Override
    public void quoteIfNeeded(StringBuilder buf, String str)
    {
        LegacyTokenizer.quoteIfNeeded(buf, str, _delim);
    }

    @Override
    public void quote(Appendable buffer, String input)
    {
        LegacyTokenizer.quote(buffer, input);
    }

    @Override
    public String unquote(String s)
    {
        return LegacyTokenizer.unquote(s);
    }

    private class LegacyTokenizer extends StringTokenizer
    {
        private final String _string;
        private final StringBuffer _token;
        private boolean _hasToken = false;
        private int _i = 0;

        public LegacyTokenizer(String str)
        {
            super("");
            _string = str;

            if (_delim.indexOf('\'') >= 0 ||
                _delim.indexOf('"') >= 0)
                throw new Error("Can't use quotes as delimiters: " + _delim);

            _token = new StringBuffer(_string.length() > 1024 ? 512 : _string.length() / 2);
        }

        @Override
        public boolean hasMoreTokens()
        {
            // Already found a token
            if (_hasToken)
                return true;

            int state = 0;
            boolean escape = false;
            while (_i < _string.length())
            {
                char c = _string.charAt(_i++);

                switch (state)
                {
                    case 0: // Start
                        if (_delim.indexOf(c) >= 0)
                        {
                            if (_returnDelimiters)
                            {
                                _token.append(c);
                                return _hasToken = true;
                            }
                        }
                        else if (c == '\'' && _singleQuotes)
                        {
                            if (_returnQuotes)
                                _token.append(c);
                            state = 2;
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
                        break;

                    case 1: // Token
                        _hasToken = true;
                        if (_delim.indexOf(c) >= 0)
                        {
                            if (_returnDelimiters)
                                _i--;
                            return _hasToken;
                        }
                        else if (c == '\'' && _singleQuotes)
                        {
                            if (_returnQuotes)
                                _token.append(c);
                            state = 2;
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
                        break;

                    case 2: // Single Quote
                        _hasToken = true;
                        if (escape)
                        {
                            escape = false;
                            _token.append(c);
                        }
                        else if (c == '\'')
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
                        break;

                    case 3: // Double Quote
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
                        break;

                    default:
                        throw new IllegalStateException();
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
            throw new UnsupportedOperationException();
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

        public static boolean needsQuoting(char c, String delim)
        {
            return c == '\\' || c == '"' || c == '\'' || Character.isWhitespace(c) || delim.indexOf(c) >= 0;
        }

        /**
         * Quote a string.
         * The string is quoted only if quoting is required due to
         * embedded delimiters, quote characters or the
         * empty string.
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
                if (needsQuoting(c, delim))
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
            // check for delimiters in input string
            int len = str.length();
            if (len == 0)
                return;

            int ch;
            for (int i = 0; i < len; i++)
            {
                ch = str.codePointAt(i);
                if (delim.indexOf(ch) >= 0)
                {
                    // found a delimiter codepoint. we need to quote it.
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

        private static final char[] escapes = new char[32];

        static
        {
            Arrays.fill(escapes, (char)0xFFFF);
            escapes['\b'] = 'b';
            escapes['\t'] = 't';
            escapes['\n'] = 'n';
            escapes['\f'] = 'f';
            escapes['\r'] = 'r';
        }

        /**
         * Quote a string into an Appendable.
         * The characters ", \, \n, \r, \t, \f and \b are escaped
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
                    if (c >= 32)
                    {
                        if (c == '"' || c == '\\')
                            buffer.append('\\');
                        buffer.append(c);
                    }
                    else
                    {
                        char escape = escapes[c];
                        if (escape == 0xFFFF)
                        {
                            // Unicode escape
                            buffer.append('\\').append('u').append('0').append('0');
                            if (c < 0x10)
                                buffer.append('0');
                            buffer.append(Integer.toString(c, 16));
                        }
                        else
                        {
                            buffer.append('\\').append(escape);
                        }
                    }
                }
                buffer.append('"');
            }
            catch (IOException x)
            {
                throw new RuntimeException(x);
            }
        }

        public static String unquote(String s)
        {
            return unquote(s, false);
        }

        /**
         * Unquote a string.
         *
         * @param s The string to unquote.
         * @param lenient true if unquoting should be lenient to escaped content, leaving some alone, false if string unescaping
         * @return quoted string
         */
        public static String unquote(String s, boolean lenient)
        {
            if (s == null)
                return null;
            if (s.length() < 2)
                return s;

            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if (first != last || (first != '"' && first != '\''))
                return s;

            StringBuilder b = new StringBuilder(s.length() - 2);
            boolean escape = false;
            for (int i = 1; i < s.length() - 1; i++)
            {
                char c = s.charAt(i);

                if (escape)
                {
                    escape = false;
                    switch (c)
                    {
                        case 'n':
                            b.append('\n');
                            break;
                        case 'r':
                            b.append('\r');
                            break;
                        case 't':
                            b.append('\t');
                            break;
                        case 'f':
                            b.append('\f');
                            break;
                        case 'b':
                            b.append('\b');
                            break;
                        case '\\':
                            b.append('\\');
                            break;
                        case '/':
                            b.append('/');
                            break;
                        case '"':
                            b.append('"');
                            break;
                        case 'u':
                            b.append((char)(
                                    (TypeUtil.convertHexDigit((byte)s.charAt(i++)) << 24) +
                                        (TypeUtil.convertHexDigit((byte)s.charAt(i++)) << 16) +
                                        (TypeUtil.convertHexDigit((byte)s.charAt(i++)) << 8) +
                                        (TypeUtil.convertHexDigit((byte)s.charAt(i++)))
                                )
                            );
                            break;
                        default:
                            if (lenient && !isValidEscaping(c))
                            {
                                b.append('\\');
                            }
                            b.append(c);
                    }
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
         * Check that char c (which is preceded by a backslash) is a valid
         * escape sequence.
         */
        private static boolean isValidEscaping(char c)
        {
            return ((c == 'n') || (c == 'r') || (c == 't') ||
                (c == 'f') || (c == 'b') || (c == '\\') ||
                (c == '/') || (c == '"') || (c == 'u'));
        }
    }
}
