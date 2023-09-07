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

/**
 * <p>A parser for {@code Set-Cookie} header values following
 * <a href="https://datatracker.ietf.org/doc/html/rfc6265">RFC 6265</a>.</p>
 * <p>White spaces around cookie name and value, and around attribute
 * name and value, are permitted but stripped.
 * Cookie values and attribute values may be quoted with double-quotes.</p>
 */
public class RFC6265SetCookieParser implements SetCookieParser
{
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
                        return null;
                    if (ch == '=')
                    {
                        name = setCookieValue.substring(offset, i).trim();
                        if (name.isEmpty())
                            return null;
                        offset = i + 1;
                        state = State.VALUE_START;
                    }
                }
                case VALUE_START ->
                {
                    if (Character.isWhitespace(ch))
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
                    if (Character.isWhitespace(ch))
                        continue;
                    if (ch != ';')
                        return null;
                    offset = i + 1;
                    state = State.ATTRIBUTE_NAME;
                }
                case ATTRIBUTE_NAME ->
                {
                    HttpTokens.Token token = HttpTokens.getToken(ch);
                    if (token == null || token.getType() == HttpTokens.Type.CNTL)
                        return null;
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
                    if (Character.isWhitespace(ch))
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

    private boolean setAttribute(HttpCookie.Builder cookie, String name, String value)
    {
        try
        {
            cookie.attribute(name, value);
            return true;
        }
        catch (Throwable x)
        {
            return false;
        }
    }

    private enum State
    {
        NAME, VALUE_START, VALUE, ATTRIBUTE, ATTRIBUTE_NAME, ATTRIBUTE_VALUE_START, ATTRIBUTE_VALUE
    }
}
