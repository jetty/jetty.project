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

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http.CookieCompliance.Violation.BAD_QUOTES;
import static org.eclipse.jetty.http.CookieCompliance.Violation.COMMA_NOT_VALID_OCTET;
import static org.eclipse.jetty.http.CookieCompliance.Violation.ESCAPE_IN_QUOTES;
import static org.eclipse.jetty.http.CookieCompliance.Violation.INVALID_COOKIES;
import static org.eclipse.jetty.http.CookieCompliance.Violation.RESERVED_NAMES_NOT_DOLLAR_PREFIXED;
import static org.eclipse.jetty.http.CookieCompliance.Violation.SPECIAL_CHARS_IN_QUOTES;

/**
 * Cookie parser
 */
@Deprecated
public class CookieCutter implements CookieParser
{
    private static final Logger LOG = LoggerFactory.getLogger(CookieCutter.class);

    private final CookieParser.Handler _handler;
    private final CookieCompliance _complianceMode;
    private final ComplianceViolation.Listener _complianceListener;

    public CookieCutter(CookieParser.Handler handler, CookieCompliance compliance, ComplianceViolation.Listener complianceListener)
    {
        _handler = handler;
        _complianceMode = compliance;
        _complianceListener = complianceListener;
    }

    @Override
    public void parseField(String field)
    {
        parseFields(Collections.singletonList(field));
    }

    @Override
    public void parseFields(List<String> rawFields)
    {
        StringBuilder unquoted = null;

        // For each cookie field
        for (String hdr : rawFields)
        {
            // Parse the header
            String name = null;

            String cookieName = null;
            String cookieValue = null;
            String cookiePath = null;
            String cookieDomain = null;
            String cookieComment = null;
            int cookieVersion = 0;

            boolean invalue = false;
            boolean inQuoted = false;
            boolean quoted = false;
            boolean escaped = false;
            boolean reject = false;
            int tokenstart = -1;
            int tokenend = -1;
            for (int i = 0, length = hdr.length(); i <= length; i++)
            {
                char c = i == length ? 0 : hdr.charAt(i);

                // Handle quoted values for value
                if (inQuoted)
                {
                    if (escaped)
                    {
                        escaped = false;
                        if (c > 0)
                            unquoted.append(c);
                        else
                        {
                            unquoted.setLength(0);
                            inQuoted = false;
                            i--;
                        }
                        continue;
                    }

                    switch (c)
                    {
                        case '"':
                            inQuoted = false;
                            quoted = true;
                            tokenstart = i;
                            tokenend = -1;
                            break;

                        case '\\':
                            if (_complianceMode.allows(ESCAPE_IN_QUOTES))
                                reportComplianceViolation(ESCAPE_IN_QUOTES, hdr);
                            else
                                reject = true;
                            escaped = true;
                            continue;

                        case 0:
                            // unterminated quote, let's ignore quotes
                            if (_complianceMode.allows(BAD_QUOTES))
                                reportComplianceViolation(BAD_QUOTES, hdr);
                            else
                                reject = true;
                            unquoted.setLength(0);
                            inQuoted = false;
                            i--;
                            continue;

                        default:
                            if (isRFC6265RejectedCharacter(c))
                            {
                                if (_complianceMode.allows(SPECIAL_CHARS_IN_QUOTES))
                                    reportComplianceViolation(SPECIAL_CHARS_IN_QUOTES, hdr);
                                else
                                    reject = true;
                            }
                            unquoted.append(c);
                    }
                }
                else
                {
                    // Handle name and value state machines
                    if (invalue)
                    {
                        // parse the cookie-value
                        switch (c)
                        {
                            case ' ':
                            case '\t':
                                break;

                            case ',':
                                if (COMMA_NOT_VALID_OCTET.isAllowedBy(_complianceMode))
                                    reportComplianceViolation(COMMA_NOT_VALID_OCTET, "Cookie " + cookieName);
                                else
                                {
                                    if (quoted)
                                    {
                                        // must have been a bad internal quote. let's fix as best we can
                                        if (_complianceMode.allows(BAD_QUOTES))
                                            reportComplianceViolation(BAD_QUOTES, hdr);
                                        else
                                            reject = true;
                                        unquoted.append(hdr, tokenstart, i--);
                                        inQuoted = true;
                                        quoted = false;
                                        continue;
                                    }
                                    if (tokenstart < 0)
                                        tokenstart = i;
                                    tokenend = i;
                                    continue;
                                }
                                // fall through
                            case 0:
                            case ';':
                            {
                                String value;

                                if (quoted)
                                {
                                    value = unquoted.toString();
                                    unquoted.setLength(0);
                                    quoted = false;
                                }
                                else if (tokenstart >= 0)
                                    value = tokenend >= tokenstart ? hdr.substring(tokenstart, tokenend + 1) : hdr.substring(tokenstart);
                                else
                                    value = "";

                                try
                                {
                                    if (name != null && name.startsWith("$"))
                                    {
                                        if (RESERVED_NAMES_NOT_DOLLAR_PREFIXED.isAllowedBy(_complianceMode))
                                        {
                                            reportComplianceViolation(RESERVED_NAMES_NOT_DOLLAR_PREFIXED, "Cookie " + cookieName + " field " + name);
                                            String lowercaseName = name.toLowerCase(Locale.ENGLISH);
                                            switch (lowercaseName)
                                            {
                                                case "$path" -> cookiePath = value;
                                                case "$domain" -> cookieDomain = value;
                                                case "$port" -> cookieComment = "$port=" + value;
                                                case "$version" -> cookieVersion = Integer.parseInt(value);
                                            }
                                        }
                                    }
                                    else
                                    {
                                        // This is a new cookie, so add the completed last cookie if we have one
                                        if (cookieName != null)
                                        {
                                            if (!isLegacyCookieName(cookieName))
                                            {
                                                if (!_complianceMode.allows(INVALID_COOKIES))
                                                    throw new InvalidCookieException("Bad Cookie Name");
                                                reportComplianceViolation(INVALID_COOKIES, hdr);
                                                reject = true;
                                            }

                                            if (reject)
                                            {
                                                if (_complianceMode.allows(INVALID_COOKIES))
                                                    reportComplianceViolation(INVALID_COOKIES, hdr);
                                                else
                                                    throw new InvalidCookieException("Bad Cookie");
                                            }
                                            else
                                            {
                                                _handler.addCookie(cookieName, cookieValue, cookieVersion, cookieDomain, cookiePath, cookieComment);
                                            }
                                            reject = false;
                                            cookieDomain = null;
                                            cookiePath = null;
                                            cookieComment = null;
                                        }
                                        cookieName = name;
                                        cookieValue = value;
                                    }
                                }
                                catch (Exception e)
                                {
                                    LOG.debug("Unable to process Cookie", e);
                                }

                                name = null;
                                tokenstart = -1;
                                invalue = false;

                                break;
                            }

                            case '"':
                                if (tokenstart < 0)
                                {
                                    tokenstart = i;
                                    inQuoted = true;
                                    if (unquoted == null)
                                        unquoted = new StringBuilder();
                                    break;
                                }
                                // fall through to default case

                            default:
                                if (quoted)
                                {
                                    // must have been a bad internal quote. let's fix as best we can
                                    if (_complianceMode.allows(BAD_QUOTES))
                                        reportComplianceViolation(BAD_QUOTES, hdr);
                                    else
                                        reject = true;
                                    unquoted.append(hdr, tokenstart, i--);
                                    inQuoted = true;
                                    quoted = false;
                                    continue;
                                }

                                if (isRFC6265RejectedCharacter(c))
                                {
                                    if (c < 128 && _complianceMode.allows(SPECIAL_CHARS_IN_QUOTES))
                                        reportComplianceViolation(SPECIAL_CHARS_IN_QUOTES, hdr);
                                    else
                                        reject = true;
                                }

                                if (tokenstart < 0)
                                    tokenstart = i;
                                tokenend = i;
                        }
                    }
                    else
                    {
                        // parse the cookie-name
                        switch (c)
                        {
                            case 0:
                            case ' ':
                            case '\t':
                                continue;

                            case '"':
                                // Quoted name is not allowed in any version of the Cookie spec
                                reject = true;
                                break;

                            case ';':
                                // a cookie terminated with no '=' sign.
                                tokenstart = -1;
                                invalue = false;
                                reject = false;
                                continue;

                            case '=':
                                if (quoted)
                                {
                                    name = unquoted.toString();
                                    unquoted.setLength(0);
                                    quoted = false;
                                }
                                else if (tokenstart >= 0)
                                    name = tokenend >= tokenstart ? hdr.substring(tokenstart, tokenend + 1) : hdr.substring(tokenstart);

                                tokenstart = -1;
                                invalue = true;
                                break;

                            default:
                                if (quoted)
                                {
                                    // must have been a bad internal quote. let's fix as best we can
                                    if (_complianceMode.allows(BAD_QUOTES))
                                        reportComplianceViolation(BAD_QUOTES, hdr);
                                    else
                                        reject = true;
                                    unquoted.append(hdr, tokenstart, i--);
                                    inQuoted = true;
                                    quoted = false;
                                    continue;
                                }

                                if (isRFC6265RejectedCharacter(c))
                                {
                                    if (_complianceMode.allows(SPECIAL_CHARS_IN_QUOTES))
                                        reportComplianceViolation(SPECIAL_CHARS_IN_QUOTES, hdr);
                                    else
                                        reject = true;
                                }

                                if (tokenstart < 0)
                                    tokenstart = i;
                                tokenend = i;
                        }
                    }
                }
            }

            if (cookieName != null)
            {
                if (reject)
                {
                    if (_complianceMode.allows(INVALID_COOKIES))
                        reportComplianceViolation(INVALID_COOKIES, hdr);
                    else
                        throw new InvalidCookieException("Bad Cookie");
                }
                else
                {
                    _handler.addCookie(cookieName, cookieValue, cookieVersion, cookieDomain, cookiePath, cookieComment);
                }
            }
        }
    }

    protected void reportComplianceViolation(CookieCompliance.Violation violation, String reason)
    {
        if (_complianceListener != null)
            _complianceListener.onComplianceViolation(new ComplianceViolation.Event(_complianceMode, violation, reason));
    }

    protected boolean isRFC6265RejectedCharacter(char c)
    {
        /* From RFC6265 - Section 4.1.1 - Syntax
         *  cookie-octet  = %x21 / %x23-2B / %x2D-3A / %x3C-5B / %x5D-7E
         *                  ; US-ASCII characters excluding CTLs,
         *                  ; whitespace DQUOTE, comma, semicolon,
         *                  ; and backslash
         */
        return Character.isISOControl(c) || // control characters
            c > 127 || // 8-bit characters
            c == ' ' || // whitespace
            c == '"' || // DQUOTE
            c == ',' || // comma
            c == ';' || // semicolon
            c == '\\';  // backslash
    }

    private boolean isLegacyCookieNameToken(char c)
    {
        return c >= 0x20 && c < 0x7f && "/()<>@,;:\\\"[]?={} \t".indexOf(c) == -1;
    }

    private boolean isLegacyCookieName(String name)
    {
        for (int i = 0; i < name.length(); i++)
            if (!isLegacyCookieNameToken(name.charAt(i)))
                return false;
        return !name.equalsIgnoreCase("Comment") && // rfc2019
            !name.equalsIgnoreCase("Discard") && // 2019++
            !name.equalsIgnoreCase("Domain") &&
            !name.equalsIgnoreCase("Expires") && // (old cookies)
            !name.equalsIgnoreCase("Max-Age") && // rfc2019
            !name.equalsIgnoreCase("Path") &&
            !name.equalsIgnoreCase("Secure") &&
            !name.equalsIgnoreCase("Version") &&
            !name.startsWith("$");
    }
}
