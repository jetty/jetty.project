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

package org.eclipse.jetty.http;

import java.time.Instant;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A parser for {@code Set-Cookie} header values following
 * <a href="https://datatracker.ietf.org/doc/html/rfc6265">RFC 6265</a>.</p>
 * <p>White spaces around cookie name and value, and around attribute
 * name and value, are permitted but stripped.
 * Cookie values and attribute values may be quoted with double-quotes.</p>
 */
public class RFC6265SetCookieParser implements SetCookieParser
{
    private static final Logger LOG = LoggerFactory.getLogger(RFC6265SetCookieParser.class);

    @Override
    public HttpCookie parse(String setCookieValue)
    {
        // Implementation of the algorithm from RFC 6265, section 5.2.

        // Parser state.
        State state = State.NAME;
        String name = null;
        boolean quoted = false;
        HttpCookie.Builder cookie = null;
        int offset = 0;
        int length = setCookieValue.length();

        // Parse.
        for (int i = 0; i < length; ++i)
        {
            char ch = setCookieValue.charAt(i);
            switch (state)
            {
                case NAME ->
                {
                    HttpTokens.Token token = HttpTokens.getToken(ch);
                    if (token == null)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("invalid character {} at index {} of {}", ch, i, setCookieValue);
                        return null;
                    }
                    if (ch == '=')
                    {
                        name = setCookieValue.substring(offset, i).trim();
                        if (name.isEmpty())
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("invalid empty cookie name at index {} of {}", i, setCookieValue);
                            return null;
                        }
                        offset = i + 1;
                        state = State.VALUE_START;
                    }
                }
                case VALUE_START ->
                {
                    if (isWhitespace(ch))
                        continue;
                    if (ch == '"')
                        quoted = true;
                    else
                        --i;
                    offset = i + 1;
                    state = State.VALUE;
                }
                case VALUE ->
                {
                    if (quoted && ch == '"')
                    {
                        quoted = false;
                        String value = setCookieValue.substring(offset, i).trim();
                        cookie = HttpCookie.build(name, value);
                        offset = i + 1;
                        state = State.ATTRIBUTE;
                    }
                    else
                    {
                        if (ch == ';')
                        {
                            String value = setCookieValue.substring(offset, i).trim();
                            cookie = HttpCookie.build(name, value);
                            offset = i + 1;
                            state = State.ATTRIBUTE_NAME;
                        }
                    }
                }
                case ATTRIBUTE ->
                {
                    if (isWhitespace(ch))
                        continue;
                    if (ch != ';')
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("invalid character {} at index {} of {}", ch, i, setCookieValue);
                        return null;
                    }
                    offset = i + 1;
                    state = State.ATTRIBUTE_NAME;
                }
                case ATTRIBUTE_NAME ->
                {
                    HttpTokens.Token token = HttpTokens.getToken(ch);
                    if (token == null || token.getType() == HttpTokens.Type.CNTL)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("invalid character {} at index {} of {}", ch, i, setCookieValue);
                        return null;
                    }
                    if (ch == '=')
                    {
                        name = setCookieValue.substring(offset, i).trim();
                        offset = i + 1;
                        state = State.ATTRIBUTE_VALUE_START;
                    }
                    else if (ch == ';')
                    {
                        name = setCookieValue.substring(offset, i).trim();
                        if (!setAttribute(cookie, name, ""))
                            return null;
                        offset = i + 1;
                        // Stay in the ATTRIBUTE_NAME state.
                    }
                }
                case ATTRIBUTE_VALUE_START ->
                {
                    if (isWhitespace(ch))
                        continue;
                    if (ch == '"')
                        quoted = true;
                    else
                        --i;
                    offset = i + 1;
                    state = State.ATTRIBUTE_VALUE;
                }
                case ATTRIBUTE_VALUE ->
                {
                    if (quoted && ch == '"')
                    {
                        quoted = false;
                        String value = setCookieValue.substring(offset, i).trim();
                        if (!setAttribute(cookie, name, value))
                            return null;
                        offset = i + 1;
                        state = State.ATTRIBUTE;
                    }
                    else
                    {
                        if (ch == ';')
                        {
                            String value = setCookieValue.substring(offset, i).trim();
                            if (!setAttribute(cookie, name, value))
                                return null;
                            offset = i + 1;
                            state = State.ATTRIBUTE_NAME;
                        }
                    }
                }
                default -> throw new IllegalStateException("invalid state " + state);
            }
        }

        return switch (state)
        {
            case NAME -> null;
            case VALUE_START -> HttpCookie.from(name, "");
            case VALUE -> HttpCookie.from(name, setCookieValue.substring(offset, length).trim());
            case ATTRIBUTE -> cookie.build();
            case ATTRIBUTE_NAME -> setAttribute(cookie, setCookieValue.substring(offset, length).trim(), "") ? cookie.build() : null;
            case ATTRIBUTE_VALUE_START -> setAttribute(cookie, name, "") ? cookie.build() : null;
            case ATTRIBUTE_VALUE -> setAttribute(cookie, name, setCookieValue.substring(offset, length).trim()) ? cookie.build() : null;
        };
    }

    private static boolean isWhitespace(char ch)
    {
        // A little more forgiving than RFC 6265.
        return ch == ' ' || ch == '\t';
    }

    /**
     * <p>Applies a parsed {@code Set-Cookie} attribute to the cookie being built, following the
     * RFC 6265 (section 5.2) parsing rules.</p>
     * <p>For the attributes that RFC 6265 says to "ignore the cookie-av" on a malformed value
     * ({@code Max-Age}, section 5.2.2; {@code Expires}, section 5.2.1; {@code Domain}, section 5.2.3),
     * a malformed value is skipped and the rest of the cookie is retained, so this method returns
     * {@code true}. For any other attribute, a value that cannot be applied rejects the whole
     * {@code Set-Cookie}, so this method returns {@code false}.</p>
     *
     * @param cookie the cookie builder to update
     * @param name the attribute name
     * @param value the attribute value (possibly empty), with any surrounding quotes already stripped
     * @return {@code true} if the attribute was applied or safely ignored and parsing may continue;
     *         {@code false} if the whole {@code Set-Cookie} must be rejected
     */
    private boolean setAttribute(HttpCookie.Builder cookie, String name, String value)
    {
        try
        {
            switch (name.toLowerCase(Locale.ROOT))
            {
                case "max-age" ->
                {
                    // RFC 6265, section 5.2.2: the value must be an optional '-' followed by one or more
                    // digits; otherwise ignore the cookie-av (keep the cookie without a Max-Age).
                    long maxAge = parseMaxAge(value);
                    if (maxAge >= 0)
                        cookie.maxAge(maxAge);
                }
                case "expires" ->
                {
                    // RFC 6265, section 5.2.1: if the value fails to parse as a cookie-date,
                    // ignore the cookie-av (keep the cookie without an Expires).
                    Instant expires;
                    try
                    {
                        expires = HttpCookie.parseExpires(value);
                    }
                    catch (Throwable x)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("ignoring unparsable Expires: {}", value, x);
                        return true;
                    }
                    cookie.expires(expires);
                }
                case "domain" ->
                {
                    // RFC 6265, section 5.2.3: if the value is empty, ignore the cookie-av.
                    if (value.isEmpty())
                        return true;
                    cookie.attribute(name, value);
                }
                default -> cookie.attribute(name, value);
            }
            return true;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("could not set attribute {}={}", name, value, x);
            return false;
        }
    }

    /**
     * <p>Parses an RFC 6265 (section 5.2.2) {@code Max-Age} value using a character scan, so that an
     * all-digit value that overflows a {@code long} is clamped to {@link Long#MAX_VALUE} rather than
     * throwing a {@link NumberFormatException}.</p>
     * <p>A valid value is an optional leading {@code '-'} followed by one or more ASCII digits; a
     * value of zero or less means the cookie is already expired and is returned as {@code 0}.</p>
     *
     * @param value the {@code Max-Age} attribute value
     * @return the Max-Age in seconds, clamped to the range {@code [0, Long.MAX_VALUE]}; or {@code -1}
     *         if the value is not a valid {@code Max-Age} and the cookie-av must be ignored
     */
    private static long parseMaxAge(String value)
    {
        if (value == null || value.isEmpty())
            return -1;
        int i = 0;
        boolean negative = value.charAt(0) == '-';
        if (negative)
        {
            // A lone '-' is not a valid number.
            if (value.length() == 1)
                return -1;
            i = 1;
        }
        long seconds = 0;
        for (; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (c < '0' || c > '9')
                return -1;
            if (!negative)
            {
                int digit = c - '0';
                // Clamp on overflow: a huge Max-Age represents a far-future (non-expiring) cookie.
                if (seconds > (Long.MAX_VALUE - digit) / 10)
                    return Long.MAX_VALUE;
                seconds = seconds * 10 + digit;
            }
        }
        // RFC 6265, section 5.2.2: a Max-Age of zero or less means the cookie is already expired.
        return negative ? 0 : seconds;
    }

    private enum State
    {
        NAME, VALUE_START, VALUE, ATTRIBUTE, ATTRIBUTE_NAME, ATTRIBUTE_VALUE_START, ATTRIBUTE_VALUE
    }
}
