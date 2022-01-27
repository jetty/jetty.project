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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http.CookieCompliance.Violation.COMMA_NOT_VALID_OCTET;
import static org.eclipse.jetty.http.CookieCompliance.Violation.RESERVED_NAMES_NOT_DOLLAR_PREFIXED;

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

    protected void parseFields(List<String> rawFields)
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
                            escaped = true;
                            continue;

                        case 0:
                            // unterminated quote, let's ignore quotes
                            unquoted.setLength(0);
                            inQuoted = false;
                            i--;
                            continue;

                        default:
                            unquoted.append(c);
                            continue;
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
                                    if (name.startsWith("$"))
                                    {
                                        if (RESERVED_NAMES_NOT_DOLLAR_PREFIXED.isAllowedBy(_complianceMode))
                                        {
                                            reportComplianceViolation(RESERVED_NAMES_NOT_DOLLAR_PREFIXED, "Cookie " + cookieName + " field " + name);
                                            String lowercaseName = name.toLowerCase(Locale.ENGLISH);
                                            switch (lowercaseName)
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
                                    }
                                    else
                                    {
                                        // This is a new cookie, so add the completed last cookie if we have one
                                        if (cookieName != null)
                                        {
                                            if (!reject)
                                            {
                                                addCookie(cookieName, cookieValue, cookieDomain, cookiePath, cookieVersion, cookieComment);
                                                reject = false;
                                            }
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
                                    unquoted.append(hdr, tokenstart, i--);
                                    inQuoted = true;
                                    quoted = false;
                                    continue;
                                }

                                if (_complianceMode == CookieCompliance.RFC6265)
                                {
                                    if (isRFC6265RejectedCharacter(inQuoted, c))
                                    {
                                        reject = true;
                                    }
                                }

                                if (tokenstart < 0)
                                    tokenstart = i;
                                tokenend = i;
                                continue;
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
                                    unquoted.append(hdr, tokenstart, i--);
                                    inQuoted = true;
                                    quoted = false;
                                    continue;
                                }

                                if (_complianceMode == CookieCompliance.RFC6265)
                                {
                                    if (isRFC6265RejectedCharacter(inQuoted, c))
                                    {
                                        reject = true;
                                    }
                                }

                                if (tokenstart < 0)
                                    tokenstart = i;
                                tokenend = i;
                                continue;
                        }
                    }
                }
            }

            if (cookieName != null && !reject)
                addCookie(cookieName, cookieValue, cookieDomain, cookiePath, cookieVersion, cookieComment);
        }
    }

    protected void reportComplianceViolation(CookieCompliance.Violation violation, String reason)
    {
        if (_complianceListener != null)
        {
            _complianceListener.onComplianceViolation(_complianceMode, violation, reason);
        }
    }

    protected abstract void addCookie(String cookieName, String cookieValue, String cookieDomain, String cookiePath, int cookieVersion, String cookieComment);

    protected boolean isRFC6265RejectedCharacter(boolean inQuoted, char c)
    {
        if (inQuoted)
        {
            // We only reject if a Control Character is encountered
            if (Character.isISOControl(c))
            {
                return true;
            }
        }
        else
        {
            /* From RFC6265 - Section 4.1.1 - Syntax
             *  cookie-octet  = %x21 / %x23-2B / %x2D-3A / %x3C-5B / %x5D-7E
             *                  ; US-ASCII characters excluding CTLs,
             *                  ; whitespace DQUOTE, comma, semicolon,
             *                  ; and backslash
             */
            return Character.isISOControl(c) || // control characters
                c > 127 || // 8-bit characters
                c == ',' || // comma
                c == ';'; // semicolon
        }

        return false;
    }
}
