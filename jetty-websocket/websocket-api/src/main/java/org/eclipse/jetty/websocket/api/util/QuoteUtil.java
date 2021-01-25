//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.api.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Provide some consistent Http header value and Extension configuration parameter quoting support.
 * <p>
 * While QuotedStringTokenizer exists in jetty-util, and works great with http header values, using it in websocket-api is undesired.
 * <ul>
 * <li>Using QuotedStringTokenizer would introduce a dependency to jetty-util that would need to be exposed via the WebAppContext classloader</li>
 * <li>ABNF defined extension parameter parsing requirements of RFC-6455 (WebSocket) ABNF, is slightly different than the ABNF parsing defined in RFC-2616
 * (HTTP/1.1).</li>
 * <li>Future HTTPbis ABNF changes for parsing will impact QuotedStringTokenizer</li>
 * </ul>
 * It was decided to keep this implementation separate for the above reasons.
 */
public class QuoteUtil
{
    private static class DeQuotingStringIterator implements Iterator<String>
    {
        private enum State
        {
            START,
            TOKEN,
            QUOTE_SINGLE,
            QUOTE_DOUBLE
        }

        private final String input;
        private final String delims;
        private StringBuilder token;
        private boolean hasToken = false;
        private int i = 0;

        public DeQuotingStringIterator(String input, String delims)
        {
            this.input = input;
            this.delims = delims;
            int len = input.length();
            token = new StringBuilder(len > 1024 ? 512 : len / 2);
        }

        private void appendToken(char c)
        {
            if (hasToken)
            {
                token.append(c);
            }
            else
            {
                if (Character.isWhitespace(c))
                {
                    return; // skip whitespace at start of token.
                }
                else
                {
                    token.append(c);
                    hasToken = true;
                }
            }
        }

        @Override
        public boolean hasNext()
        {
            // already found a token
            if (hasToken)
            {
                return true;
            }

            State state = State.START;
            boolean escape = false;
            int inputLen = input.length();

            while (i < inputLen)
            {
                char c = input.charAt(i++);

                switch (state)
                {
                    case START:
                    {
                        if (c == '\'')
                        {
                            state = State.QUOTE_SINGLE;
                            appendToken(c);
                        }
                        else if (c == '\"')
                        {
                            state = State.QUOTE_DOUBLE;
                            appendToken(c);
                        }
                        else
                        {
                            appendToken(c);
                            state = State.TOKEN;
                        }
                        break;
                    }
                    case TOKEN:
                    {
                        if (delims.indexOf(c) >= 0)
                        {
                            // System.out.printf("hasNext/t: %b [%s]%n",hasToken,token);
                            return hasToken;
                        }
                        else if (c == '\'')
                        {
                            state = State.QUOTE_SINGLE;
                        }
                        else if (c == '\"')
                        {
                            state = State.QUOTE_DOUBLE;
                        }
                        appendToken(c);
                        break;
                    }
                    case QUOTE_SINGLE:
                    {
                        if (escape)
                        {
                            escape = false;
                            appendToken(c);
                        }
                        else if (c == '\'')
                        {
                            appendToken(c);
                            state = State.TOKEN;
                        }
                        else if (c == '\\')
                        {
                            escape = true;
                        }
                        else
                        {
                            appendToken(c);
                        }
                        break;
                    }
                    case QUOTE_DOUBLE:
                    {
                        if (escape)
                        {
                            escape = false;
                            appendToken(c);
                        }
                        else if (c == '\"')
                        {
                            appendToken(c);
                            state = State.TOKEN;
                        }
                        else if (c == '\\')
                        {
                            escape = true;
                        }
                        else
                        {
                            appendToken(c);
                        }
                        break;
                    }
                }
                // System.out.printf("%s <%s> : [%s]%n",state,c,token);
            }
            // System.out.printf("hasNext/e: %b [%s]%n",hasToken,token);
            return hasToken;
        }

        @Override
        public String next()
        {
            if (!hasNext())
            {
                throw new NoSuchElementException();
            }
            String ret = token.toString();
            token.setLength(0);
            hasToken = false;
            return QuoteUtil.dequote(ret.trim());
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException("Remove not supported with this iterator");
        }
    }

    /**
     * ABNF from RFC 2616, RFC 822, and RFC 6455 specified characters requiring quoting.
     */
    public static final String ABNF_REQUIRED_QUOTING = "\"'\\\n\r\t\f\b%+ ;=";

    private static final char UNICODE_TAG = 0xFFFF;
    private static final char[] escapes = new char[32];

    static
    {
        Arrays.fill(escapes, UNICODE_TAG);
        // non-unicode
        escapes['\b'] = 'b';
        escapes['\t'] = 't';
        escapes['\n'] = 'n';
        escapes['\f'] = 'f';
        escapes['\r'] = 'r';
    }

    private static int dehex(byte b)
    {
        if ((b >= '0') && (b <= '9'))
        {
            return (byte)(b - '0');
        }
        if ((b >= 'a') && (b <= 'f'))
        {
            return (byte)((b - 'a') + 10);
        }
        if ((b >= 'A') && (b <= 'F'))
        {
            return (byte)((b - 'A') + 10);
        }
        throw new IllegalArgumentException("!hex:" + Integer.toHexString(0xff & b));
    }

    /**
     * Remove quotes from a string, only if the input string start with and end with the same quote character.
     *
     * @param str the string to remove surrounding quotes from
     * @return the de-quoted string
     */
    public static String dequote(String str)
    {
        char start = str.charAt(0);
        if ((start == '\'') || (start == '\"'))
        {
            // possibly quoted
            char end = str.charAt(str.length() - 1);
            if (start == end)
            {
                // dequote
                return str.substring(1, str.length() - 1);
            }
        }
        return str;
    }

    public static void escape(StringBuilder buf, String str)
    {
        for (char c : str.toCharArray())
        {
            if (c >= 32)
            {
                // non special character
                if ((c == '"') || (c == '\\'))
                {
                    buf.append('\\');
                }
                buf.append(c);
            }
            else
            {
                // special characters, requiring escaping
                char escaped = escapes[c];

                // is this a unicode escape?
                if (escaped == UNICODE_TAG)
                {
                    buf.append("\\u00");
                    if (c < 0x10)
                    {
                        buf.append('0');
                    }
                    buf.append(Integer.toString(c, 16)); // hex
                }
                else
                {
                    // normal escape
                    buf.append('\\').append(escaped);
                }
            }
        }
    }

    /**
     * Simple quote of a string, escaping where needed.
     *
     * @param buf the StringBuilder to append to
     * @param str the string to quote
     */
    public static void quote(StringBuilder buf, String str)
    {
        buf.append('"');
        escape(buf, str);
        buf.append('"');
    }

    /**
     * Append into buf the provided string, adding quotes if needed.
     * <p>
     * Quoting is determined if any of the characters in the <code>delim</code> are found in the input <code>str</code>.
     *
     * @param buf the buffer to append to
     * @param str the string to possibly quote
     * @param delim the delimiter characters that will trigger automatic quoting
     */
    public static void quoteIfNeeded(StringBuilder buf, String str, String delim)
    {
        if (str == null)
        {
            return;
        }
        // check for delimiters in input string
        int len = str.length();
        if (len == 0)
        {
            return;
        }
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
     * Create an iterator of the input string, breaking apart the string at the provided delimiters, removing quotes and triming the parts of the string as
     * needed.
     *
     * @param str the input string to split apart
     * @param delims the delimiter characters to split the string on
     * @return the iterator of the parts of the string, trimmed, with quotes around the string part removed, and unescaped
     */
    public static Iterator<String> splitAt(String str, String delims)
    {
        return new DeQuotingStringIterator(str.trim(), delims);
    }

    public static String unescape(String str)
    {
        if (str == null)
        {
            // nothing there
            return null;
        }

        int len = str.length();
        if (len <= 1)
        {
            // impossible to be escaped
            return str;
        }

        StringBuilder ret = new StringBuilder(len - 2);
        boolean escaped = false;
        char c;
        for (int i = 0; i < len; i++)
        {
            c = str.charAt(i);
            if (escaped)
            {
                escaped = false;
                switch (c)
                {
                    case 'n':
                        ret.append('\n');
                        break;
                    case 'r':
                        ret.append('\r');
                        break;
                    case 't':
                        ret.append('\t');
                        break;
                    case 'f':
                        ret.append('\f');
                        break;
                    case 'b':
                        ret.append('\b');
                        break;
                    case '\\':
                        ret.append('\\');
                        break;
                    case '/':
                        ret.append('/');
                        break;
                    case '"':
                        ret.append('"');
                        break;
                    case 'u':
                        ret.append((char)((dehex((byte)str.charAt(i++)) << 24) + (dehex((byte)str.charAt(i++)) << 16) + (dehex((byte)str.charAt(i++)) << 8) + (dehex((byte)str
                            .charAt(i++)))));
                        break;
                    default:
                        ret.append(c);
                }
            }
            else if (c == '\\')
            {
                escaped = true;
            }
            else
            {
                ret.append(c);
            }
        }
        return ret.toString();
    }

    public static String join(Object[] objs, String delim)
    {
        if (objs == null)
        {
            return "";
        }
        StringBuilder ret = new StringBuilder();
        int len = objs.length;
        for (int i = 0; i < len; i++)
        {
            if (i > 0)
            {
                ret.append(delim);
            }
            if (objs[i] instanceof String)
            {
                ret.append('"').append(objs[i]).append('"');
            }
            else
            {
                ret.append(objs[i]);
            }
        }
        return ret.toString();
    }

    public static String join(Collection<?> objs, String delim)
    {
        if (objs == null)
        {
            return "";
        }
        StringBuilder ret = new StringBuilder();
        boolean needDelim = false;
        for (Object obj : objs)
        {
            if (needDelim)
            {
                ret.append(delim);
            }
            if (obj instanceof String)
            {
                ret.append('"').append(obj).append('"');
            }
            else
            {
                ret.append(obj);
            }
            needDelim = true;
        }
        return ret.toString();
    }
}
