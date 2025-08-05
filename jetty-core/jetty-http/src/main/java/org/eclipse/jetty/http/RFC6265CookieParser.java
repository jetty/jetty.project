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

import java.util.Locale;

import org.eclipse.jetty.util.StringUtil;

import static org.eclipse.jetty.http.CookieCompliance.Violation.ATTRIBUTES;
import static org.eclipse.jetty.http.CookieCompliance.Violation.ATTRIBUTE_VALUES;
import static org.eclipse.jetty.http.CookieCompliance.Violation.COMMA_NOT_VALID_OCTET;
import static org.eclipse.jetty.http.CookieCompliance.Violation.COMMA_SEPARATOR;
import static org.eclipse.jetty.http.CookieCompliance.Violation.ESCAPE_IN_QUOTES;
import static org.eclipse.jetty.http.CookieCompliance.Violation.INVALID_COOKIES;
import static org.eclipse.jetty.http.CookieCompliance.Violation.OPTIONAL_WHITE_SPACE;
import static org.eclipse.jetty.http.CookieCompliance.Violation.SPACE_IN_VALUES;
import static org.eclipse.jetty.http.CookieCompliance.Violation.SPECIAL_CHARS_IN_QUOTES;

/**
 * Cookie parser
 */
public class RFC6265CookieParser implements CookieParser
{
    private final CookieParser.Handler _handler;
    private final CookieCompliance _complianceMode;
    private final ComplianceViolation.Listener _complianceListener;

    protected RFC6265CookieParser(CookieParser.Handler handler, CookieCompliance compliance, ComplianceViolation.Listener complianceListener)
    {
        _handler = handler;
        _complianceMode = compliance;
        _complianceListener = complianceListener;
    }

    private enum State
    {
        START,
        IN_NAME,
        AFTER_NAME,
        VALUE,
        IN_VALUE,
        SPACE_IN_VALUE,
        IN_QUOTED_VALUE,
        ESCAPED_VALUE,
        AFTER_QUOTED_VALUE,
        END,
        INVALID_COOKIE
    }

    @Override
    public void parseField(String field)
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
        boolean cookieInvalid = false;
        int spaces = 0;

        boolean supportOldAttributes = (_complianceMode.allows(ATTRIBUTE_VALUES) || _complianceMode.allows(ATTRIBUTES));

        int length = field.length();
        StringBuilder string = new StringBuilder();
        for (int i = 0; i <= length; i++)
        {
            char c = i == length ? ';' : field.charAt(i);
            HttpTokens.Token token = HttpTokens.getToken(c);

            if (token == null)
            {
                if (!_complianceMode.allows(INVALID_COOKIES))
                     throw new InvalidCookieException("Invalid Cookie character: 0x%02x [%s]: %s".formatted((byte)c, c, field));
                state = State.INVALID_COOKIE;
                continue;
            }

            switch (state)
            {
                case START:
                    if (c == ' ' || c == '\t' || c == ';')
                        continue;

                    string.setLength(0);

                    if (token.isRfc2616Token())
                    {
                        if (StringUtil.isNotBlank(cookieName) && !(c == '$' && supportOldAttributes))
                        {
                            _handler.addCookie(cookieName, cookieValue, cookieVersion, cookieDomain, cookiePath, cookieComment);
                            cookieName = null;
                            cookieValue = null;
                            cookieDomain = null;
                            cookiePath = null;
                            cookieComment = null;
                        }

                        string.append(c);
                        state = State.IN_NAME;
                    }
                    else if (_complianceMode.allows(INVALID_COOKIES))
                    {
                        reportComplianceViolation(INVALID_COOKIES, field);
                        state = State.INVALID_COOKIE;
                    }
                    else
                    {
                        throw new InvalidCookieException("Bad Cookie name");
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

                    if ((c == ' ' || c == '\t') && _complianceMode.allows(OPTIONAL_WHITE_SPACE))
                    {
                        reportComplianceViolation(OPTIONAL_WHITE_SPACE, field);
                        if (string.charAt(0) == '$')
                            attributeName = string.toString();
                        else
                            cookieName = string.toString();
                        state = State.AFTER_NAME;
                        continue;
                    }

                    if (token.isRfc2616Token())
                    {
                        string.append(c);
                    }
                    else if (_complianceMode.allows(INVALID_COOKIES))
                    {
                        reportComplianceViolation(INVALID_COOKIES, string.toString());
                        state = c == ';' ? State.START : State.INVALID_COOKIE;
                    }
                    else
                    {
                        throw new InvalidCookieException("Bad Cookie name");
                    }
                    break;

                case AFTER_NAME:
                    if (c == '=')
                    {
                        state = State.VALUE;
                        continue;
                    }
                    if (c == ';' || c == ',')
                    {
                        state = State.START;
                        continue;
                    }

                    if (_complianceMode.allows(INVALID_COOKIES))
                    {
                        reportComplianceViolation(INVALID_COOKIES, field);
                        state = State.INVALID_COOKIE;
                    }
                    else
                    {
                        throw new InvalidCookieException("Bad Cookie");
                    }
                    break;

                case VALUE:
                    if (c == ' ' && _complianceMode.allows(OPTIONAL_WHITE_SPACE))
                    {
                        reportComplianceViolation(OPTIONAL_WHITE_SPACE, field);
                        continue;
                    }

                    string.setLength(0);
                    if (c == '"')
                    {
                        state = State.IN_QUOTED_VALUE;
                    }
                    else if (c == ';')
                    {
                        value = "";
                        i--;
                        state = State.END;
                    }
                    else if (token.isRfc6265CookieOctet())
                    {
                        string.append(c);
                        state = State.IN_VALUE;
                    }
                    else if (_complianceMode.allows(INVALID_COOKIES))
                    {
                        reportComplianceViolation(INVALID_COOKIES, "Illegal character '%s' in %s".formatted(c, field));
                        state = State.INVALID_COOKIE;
                    }
                    else
                    {
                        throw new InvalidCookieException("Bad Cookie value");
                    }
                    break;

                case IN_VALUE:
                    if (c == ' ' && _complianceMode.allows(SPACE_IN_VALUES))
                    {
                        reportComplianceViolation(SPACE_IN_VALUES, field);
                        spaces = 1;
                        state = State.SPACE_IN_VALUE;
                    }
                    else if (c == ' ' || c == ';' || c == ',' || c == '\t')
                    {
                        value = string.toString();
                        i--;
                        state = State.END;
                    }
                    else if (token.isRfc6265CookieOctet())
                    {
                        string.append(c);
                    }
                    else if (_complianceMode.allows(INVALID_COOKIES))
                    {
                        reportComplianceViolation(INVALID_COOKIES, "Illegal character '%s' in %s".formatted(c, field));
                        state = State.INVALID_COOKIE;
                    }
                    else
                    {
                        throw new InvalidCookieException("Bad Cookie value");
                    }
                    break;

                case SPACE_IN_VALUE:
                    if (c == ' ')
                    {
                        spaces++;
                    }
                    else if (c == ';' || c == ',' || c == '\t')
                    {
                        value = string.toString();
                        i--;
                        state = State.END;
                    }
                    else if (token.isRfc6265CookieOctet())
                    {
                        string.append(" ".repeat(spaces)).append(c);
                        state = State.IN_VALUE;
                    }
                    else if (_complianceMode.allows(INVALID_COOKIES))
                    {
                        reportComplianceViolation(INVALID_COOKIES, field);
                        state = State.INVALID_COOKIE;
                    }
                    else
                    {
                        throw new InvalidCookieException("Bad Cookie value");
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
                        reportComplianceViolation(SPECIAL_CHARS_IN_QUOTES, "Character [" + c + "] is not allowed - " + field);
                        string.append(c);
                    }
                    else if (c == ',' && _complianceMode.allows(COMMA_NOT_VALID_OCTET))
                    {
                        reportComplianceViolation(COMMA_NOT_VALID_OCTET, field);
                        string.append(c);
                    }
                    else if (c == ' ' && _complianceMode.allows(SPACE_IN_VALUES))
                    {
                        reportComplianceViolation(SPACE_IN_VALUES, field);
                        string.append(c);
                    }
                    else if (_complianceMode.allows(INVALID_COOKIES))
                    {
                        string.append(c);
                        if (!cookieInvalid)
                        {
                            cookieInvalid = true;
                            reportComplianceViolation(INVALID_COOKIES, "Illegal character '%s' in quoted section in %s".formatted(c, field));
                        }
                        // Try to find the closing double quote by staying in the current state.
                    }
                    else
                    {
                        throw new InvalidCookieException("Bad Cookie quoted value");
                    }
                    break;

                case ESCAPED_VALUE:
                    string.append(c);
                    state = State.IN_QUOTED_VALUE;
                    break;

                case AFTER_QUOTED_VALUE:
                    if (c == ';' || c == ',' || c == ' ' || c == '\t')
                    {
                        i--;
                        state = cookieInvalid ? State.INVALID_COOKIE : State.END;
                    }
                    else if (_complianceMode.allows(INVALID_COOKIES))
                    {
                        reportComplianceViolation(INVALID_COOKIES, field);
                        state = State.INVALID_COOKIE;
                    }
                    else
                    {
                        throw new InvalidCookieException("Bad Cookie quoted value");
                    }
                    break;

                case END:
                    if (c == ';')
                    {
                        state = State.START;
                    }
                    else if (c == ',')
                    {
                        if (_complianceMode.allows(COMMA_SEPARATOR))
                        {
                            reportComplianceViolation(COMMA_SEPARATOR, field);
                            state = State.START;
                        }
                        else if (_complianceMode.allows(INVALID_COOKIES))
                        {
                            reportComplianceViolation(INVALID_COOKIES, "Illegal character ',' in " + field);
                            state = State.INVALID_COOKIE;
                            continue;
                        }
                        else
                        {
                            throw new InvalidCookieException("Comma cookie separator");
                        }
                    }
                    else if ((c == ' ' || c == '\t') && _complianceMode.allows(OPTIONAL_WHITE_SPACE))
                    {
                        reportComplianceViolation(OPTIONAL_WHITE_SPACE, field);
                        continue;
                    }

                    if (StringUtil.isBlank(attributeName))
                    {
                        cookieValue = value;
                    }
                    else
                    {
                        // We have an attribute.
                        if (_complianceMode.allows(ATTRIBUTE_VALUES))
                        {
                            reportComplianceViolation(ATTRIBUTES, attributeName);
                            // Handle $NAME entries from the older https://www.rfc-editor.org/rfc/rfc2965#section-3.4 spec
                            switch (attributeName.toLowerCase(Locale.ENGLISH))
                            {
                                case "$path" -> cookiePath = value;
                                case "$domain" -> cookieDomain = value;
                                case "$port" -> cookieComment = "$port=" + value;
                                case "$version" -> cookieVersion = Integer.parseInt(value);
                                // Known and Valid attribute $NAMES that we don't do anything special with.
                                case "$expires", "$max-age", "$commenturl", "$comment", "$discard", "$secure" ->
                                {
                                    // ignore
                                }
                                default ->
                                {
                                    reportComplianceViolation(INVALID_COOKIES, attributeName);
                                    if (!_complianceMode.allows(INVALID_COOKIES))
                                        throw new IllegalArgumentException("Invalid Cookie attribute: " + attributeName);
                                    state = State.INVALID_COOKIE;
                                }
                            }
                        }
                        else if (_complianceMode.allows(ATTRIBUTES))
                        {
                            reportComplianceViolation(ATTRIBUTES, attributeName);
                        }
                        else
                        {
                            cookieName = attributeName;
                            cookieValue = value;
                        }
                        attributeName = null;
                    }
                    value = null;

                    if (state == State.END)
                        throw new InvalidCookieException("Invalid cookie");
                    break;

                case INVALID_COOKIE:
                    attributeName = null;
                    value = null;
                    cookieName = null;
                    cookieValue = null;
                    cookiePath = null;
                    cookieDomain = null;
                    cookieComment = null;
                    cookieInvalid = false;
                    if (c == ';')
                        state = State.START;
                    break;
            }
        }

        if (!cookieInvalid && !StringUtil.isBlank(cookieName))
            _handler.addCookie(cookieName, cookieValue, cookieVersion, cookieDomain, cookiePath, cookieComment);
    }

    protected void reportComplianceViolation(CookieCompliance.Violation violation, String reason)
    {
        if (_complianceListener != null)
            _complianceListener.onComplianceViolation(new ComplianceViolation.Event(_complianceMode, violation, reason));
    }

}
