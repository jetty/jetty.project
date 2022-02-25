//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import java.util.Objects;

/**
 * Collection of Syntax validation methods.
 * <p>
 * Use in a similar way as you would {@link java.util.Objects#requireNonNull(Object)}
 * </p>
 */
public final class Syntax
{

    /**
     * Per RFC2616: Section 2.2, a token follows these syntax rules
     * <pre>
     *  token          = 1*&lt;any CHAR except CTLs or separators&gt;
     *  CHAR           = &lt;any US-ASCII character (octets 0 - 127)&gt;
     *  CTL            = &lt;any US-ASCII control character
     *                   (octets 0 - 31) and DEL (127)&gt;
     *  separators     = "(" | ")" | "&lt;" | "&gt;" | "@"
     *                 | "," | ";" | ":" | "\" | &lt;"&gt;
     *                 | "/" | "[" | "]" | "?" | "="
     *                 | "{" | "}" | SP | HT
     * </pre>
     *
     * @param value the value to test
     * @param msg the message to be prefixed if an {@link IllegalArgumentException} is thrown.
     * @throws IllegalArgumentException if the value is invalid per spec
     */
    public static void requireValidRFC2616Token(String value, String msg)
    {
        Objects.requireNonNull(msg, "msg cannot be null");

        if (value == null)
        {
            return;
        }

        int valueLen = value.length();
        if (valueLen == 0)
        {
            return;
        }

        for (int i = 0; i < valueLen; i++)
        {
            char c = value.charAt(i);

            // 0x00 - 0x1F are low order control characters
            // 0x7F is the DEL control character
            if ((c <= 0x1F) || (c == 0x7F))
                throw new IllegalArgumentException(msg + ": RFC2616 tokens may not contain control characters");
            if (c == '(' || c == ')' || c == '<' || c == '>' || c == '@' ||
                c == ',' || c == ';' || c == ':' || c == '\\' || c == '"' ||
                c == '/' || c == '[' || c == ']' || c == '?' || c == '=' ||
                c == '{' || c == '}' || c == ' ')
            {
                throw new IllegalArgumentException(msg + ": RFC2616 tokens may not contain separator character: [" + c + "]");
            }
            if (c >= 0x80)
                throw new IllegalArgumentException(msg + ": RFC2616 tokens characters restricted to US-ASCII: 0x" + Integer.toHexString(c));
        }
    }

    /**
     * Per RFC6265, Cookie.value follows these syntax rules
     * <pre>
     *  cookie-value      = *cookie-octet / ( DQUOTE *cookie-octet DQUOTE )
     *  cookie-octet      = %x21 / %x23-2B / %x2D-3A / %x3C-5B / %x5D-7E
     *                      ; US-ASCII characters excluding CTLs,
     *                      ; whitespace DQUOTE, comma, semicolon,
     *                      ; and backslash
     * </pre>
     *
     * @param value the value to test
     * @throws IllegalArgumentException if the value is invalid per spec
     */
    public static void requireValidRFC6265CookieValue(String value)
    {
        if (value == null)
        {
            return;
        }

        int valueLen = value.length();
        if (valueLen == 0)
        {
            return;
        }

        int i = 0;
        if (value.charAt(0) == '"')
        {
            // Has starting DQUOTE
            if (valueLen <= 1 || (value.charAt(valueLen - 1) != '"'))
            {
                throw new IllegalArgumentException("RFC6265 Cookie values must have balanced DQUOTES (if used)");
            }

            // adjust search range to exclude DQUOTES
            i++;
            valueLen--;
        }
        for (; i < valueLen; i++)
        {
            char c = value.charAt(i);

            // 0x00 - 0x1F are low order control characters
            // 0x7F is the DEL control character
            if ((c <= 0x1F) || (c == 0x7F))
                throw new IllegalArgumentException("RFC6265 Cookie values may not contain control characters");
            if ((c == ' ' /* 0x20 */) ||
                (c == '"' /* 0x2C */) ||
                (c == ';' /* 0x3B */) ||
                (c == '\\' /* 0x5C */))
            {
                throw new IllegalArgumentException("RFC6265 Cookie values may not contain character: [" + c + "]");
            }
            if (c >= 0x80)
                throw new IllegalArgumentException("RFC6265 Cookie values characters restricted to US-ASCII: 0x" + Integer.toHexString(c));
        }
    }
}
