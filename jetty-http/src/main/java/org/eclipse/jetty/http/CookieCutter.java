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

import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http.CookieCompliance.Violation.COMMA_NOT_VALID_OCTET;
import static org.eclipse.jetty.http.CookieCompliance.Violation.COMMA_SEPARATOR;
import static org.eclipse.jetty.http.CookieCompliance.Violation.ESCAPE_IN_QUOTES;
import static org.eclipse.jetty.http.CookieCompliance.Violation.IGNORED_BAD_COOKIES;
import static org.eclipse.jetty.http.CookieCompliance.Violation.RESERVED_NAMES_NOT_DOLLAR_PREFIXED;
import static org.eclipse.jetty.http.CookieCompliance.Violation.SPECIAL_CHARS_IN_QUOTES;

/**
 * Cookie parser
 */
public abstract class CookieCutter
{
    protected static final Logger LOG = LoggerFactory.getLogger(CookieCutter.class);

    protected final CookieCompliance _complianceMode;
    private final ComplianceViolation.Listener _complianceListener;

    protected CookieCutter(CookieCompliance compliance, ComplianceViolation.Listener complianceListener)
    {
        _complianceMode = compliance;
        _complianceListener = complianceListener;
    }

    private enum State
    {
        START,
        IN_NAME,
        VALUE,
        IN_VALUE,
        IN_QUOTED_VALUE,
        ESCAPED_VALUE,
        AFTER_QUOTED_VALUE,
        END,
        SKIP_BAD_COOKIE
    }

    protected void parseField(String field)
    {
        State state = State.START;

        String attributeName = null;
        String value = null;
        String cookieName = null;
        String cookieValue = null;
        String cookiePath = null;
        String cookieDomain = null;
        String cookieComment = null;
        int cookieVersion = 0;

        int length = field.length();
        StringBuilder string = new StringBuilder();
        for (int i = 0; i <= length; i++)
        {
            char c = i == length ? ';' : field.charAt(i);
            HttpTokens.Token token = HttpTokens.getToken(c);

            if (token == null)
            {
                if (!_complianceMode.allows(IGNORED_BAD_COOKIES))
                     throw new IllegalArgumentException("Bad Cookie character");
                state = State.SKIP_BAD_COOKIE;
                continue;
            }

            switch (state)
            {
                case START:
                    if (c == ' ' || c == ';')
                        continue;

                    string.setLength(0);

                    if (token.isRfc2616Token())
                    {
                        if (!StringUtil.isBlank(cookieName) && c != '$')
                        {
                            addCookie(cookieName, cookieValue, cookieDomain, cookiePath, cookieVersion, cookieComment);
                            cookieName = null;
                            cookieValue = null;
                            cookieDomain = null;
                            cookiePath = null;
                            cookieComment = null;
                        }

                        string.append(c);
                        state = State.IN_NAME;
                    }
                    else if (_complianceMode.allows(IGNORED_BAD_COOKIES))
                    {
                        reportComplianceViolation(IGNORED_BAD_COOKIES, field);
                        state = State.SKIP_BAD_COOKIE;
                    }
                    else
                    {
                        throw new IllegalArgumentException("Bad Cookie name");
                    }

                    break;

                case IN_NAME:
                    if (c == '=')
                    {
                        if (string.charAt(0) == '$')
                            attributeName = string.toString();
                        else
                            cookieName = string.toString();
                        state = State.VALUE;
                        continue;
                    }

                    if (token.isRfc2616Token())
                    {
                        string.append(c);
                    }
                    else if (_complianceMode.allows(IGNORED_BAD_COOKIES))
                    {
                        reportComplianceViolation(IGNORED_BAD_COOKIES, field);
                        state = c == ';' ? State.START : State.SKIP_BAD_COOKIE;
                    }
                    else
                    {
                        throw new IllegalArgumentException("Bad Cookie name");
                    }
                    break;

                case VALUE:
                    string.setLength(0);
                    if (c == '"')
                    {
                        state = State.IN_QUOTED_VALUE;
                    }
                    else if (token.isRfc6265CookieOctet())
                    {
                        string.append(c);
                        state = State.IN_VALUE;
                    }
                    else if (_complianceMode.allows(IGNORED_BAD_COOKIES))
                    {
                        reportComplianceViolation(IGNORED_BAD_COOKIES, field);
                        state = State.SKIP_BAD_COOKIE;
                    }
                    else
                    {
                        throw new IllegalArgumentException("Bad Cookie value");
                    }
                    break;

                case IN_VALUE:
                    if (c == ';' || c == ',')
                    {
                        value = string.toString();
                        i--;
                        state = State.END;
                    }
                    else if (token.isRfc6265CookieOctet())
                    {
                        string.append(c);
                    }
                    else if (_complianceMode.allows(IGNORED_BAD_COOKIES))
                    {
                        reportComplianceViolation(IGNORED_BAD_COOKIES, field);
                        state = State.SKIP_BAD_COOKIE;
                    }
                    else
                    {
                        throw new IllegalArgumentException("Bad Cookie value");
                    }
                    break;

                case IN_QUOTED_VALUE:
                    if (c == '"')
                    {
                        value = string.toString();
                        state = State.AFTER_QUOTED_VALUE;
                    }
                    else if (c == '\\' && _complianceMode.allows(ESCAPE_IN_QUOTES))
                    {
                        state = State.ESCAPED_VALUE;
                    }
                    else if (token.isRfc6265CookieOctet())
                    {
                        string.append(c);
                    }
                    else if (_complianceMode.allows(SPECIAL_CHARS_IN_QUOTES))
                    {
                        reportComplianceViolation(SPECIAL_CHARS_IN_QUOTES, field);
                        string.append(c);
                    }
                    else if (c == ',' && _complianceMode.allows(COMMA_NOT_VALID_OCTET))
                    {
                        reportComplianceViolation(COMMA_NOT_VALID_OCTET, field);
                        string.append(c);
                    }
                    else if (_complianceMode.allows(IGNORED_BAD_COOKIES))
                    {
                        reportComplianceViolation(IGNORED_BAD_COOKIES, field);
                        state = State.SKIP_BAD_COOKIE;
                    }
                    else
                    {
                        throw new IllegalArgumentException("Bad Cookie quoted value");
                    }
                    break;

                case ESCAPED_VALUE:
                    string.append(c);
                    state = State.IN_QUOTED_VALUE;
                    break;

                case AFTER_QUOTED_VALUE:
                    if (c == ';' || c == ',')
                    {
                        i--;
                        state = State.END;
                    }
                    else if (_complianceMode.allows(IGNORED_BAD_COOKIES))
                    {
                        reportComplianceViolation(IGNORED_BAD_COOKIES, field);
                        state = State.SKIP_BAD_COOKIE;
                    }
                    else
                    {
                        throw new IllegalArgumentException("Bad Cookie quoted value");
                    }
                    break;

                case END:
                    if (!StringUtil.isBlank(value))
                    {
                        if (StringUtil.isBlank(attributeName))
                        {
                            cookieValue = value;
                        }
                        else
                        {
                            if (_complianceMode.allows(RESERVED_NAMES_NOT_DOLLAR_PREFIXED))
                            {
                                reportComplianceViolation(RESERVED_NAMES_NOT_DOLLAR_PREFIXED, field);
                                switch (attributeName.toLowerCase(Locale.ENGLISH))
                                {
                                    case "$path":
                                        cookiePath = value;
                                        break;
                                    case "$domain":
                                        cookieDomain = value;
                                        break;
                                    case "$port":
                                        cookieComment = "$port=" + value;
                                        break;
                                    case "$version":
                                        cookieVersion = Integer.parseInt(value);
                                        break;
                                    default:
                                        break;
                                }
                            }
                            else if (_complianceMode.allows(IGNORED_BAD_COOKIES))
                            {
                                reportComplianceViolation(IGNORED_BAD_COOKIES, field);
                                state = State.SKIP_BAD_COOKIE;
                            }
                            else
                            {
                                throw new IllegalArgumentException("Bad Cookie name");
                            }
                            attributeName = null;
                        }
                        value = null;
                        if (c == ';')
                            state = State.START;
                        else if (c == ',')
                        {
                            if (_complianceMode.allows(COMMA_SEPARATOR))
                            {
                                reportComplianceViolation(COMMA_SEPARATOR, field);
                                state = State.START;
                            }
                            else if (_complianceMode.allows(IGNORED_BAD_COOKIES))
                            {
                                reportComplianceViolation(IGNORED_BAD_COOKIES, field);
                                if (!StringUtil.isBlank(cookieName) && !StringUtil.isBlank(cookieValue))
                                    addCookie(cookieName, cookieValue, cookieDomain, cookiePath, cookieVersion, cookieComment);
                                state = State.SKIP_BAD_COOKIE;
                            }
                            else
                                throw new IllegalStateException("Comma cookie separator");
                        }
                        else
                            throw new IllegalStateException();
                    }
                    break;

                case SKIP_BAD_COOKIE:
                    attributeName = null;
                    value = null;
                    cookieName = null;
                    cookieValue = null;
                    cookiePath = null;
                    cookieDomain = null;
                    cookieComment = null;
                    if (c == ';')
                        state = State.START;
                    break;
            }
        }

        if (!StringUtil.isBlank(cookieName) && !StringUtil.isBlank(cookieValue))
            addCookie(cookieName, cookieValue, cookieDomain, cookiePath, cookieVersion, cookieComment);
    }

    protected void parseFields(List<String> rawFields)
    {
        // For each cookie field
        for (String field : rawFields)
            parseField(field);
    }

    protected void reportComplianceViolation(CookieCompliance.Violation violation, String reason)
    {
        if (_complianceListener != null)
        {
            _complianceListener.onComplianceViolation(_complianceMode, violation, reason);
        }
    }

    protected abstract void addCookie(String cookieName, String cookieValue, String cookieDomain, String cookiePath, int cookieVersion, String cookieComment);

}
