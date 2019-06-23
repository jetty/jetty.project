//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import static org.eclipse.jetty.http.CookieCompliance.Violation.COMMA_NOT_VALID_OCTET;
import static org.eclipse.jetty.http.CookieCompliance.Violation.RESERVED_NAMES_NOT_DOLLAR_PREFIXED;

/**
 * Cookie parser
 */
public abstract class CookieCutter
{
    protected static final Logger LOG = Log.getLogger(CookieCutter.class);

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
            int tokenstart = -1;
            int tokenend = -1;
            for (int i = 0, length = hdr.length(); i <= length; i++)
            {
                char c = i == length ? 0 : hdr.charAt(i);

                // System.err.printf("i=%d/%d c=%s v=%b q=%b/%b e=%b u=%s s=%d e=%d \t%s=%s%n" ,i,length,c==0?"|":(""+c),invalue,inQuoted,quoted,escaped,unquoted,tokenstart,tokenend,name,value);

                // Handle quoted values for name or value
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
                        // parse the value
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
                                            addCookie(cookieName, cookieValue, cookieDomain, cookiePath, cookieVersion, cookieComment);
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
                                    LOG.debug(e);
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
                                if (tokenstart < 0)
                                    tokenstart = i;
                                tokenend = i;
                                continue;
                        }
                    }
                    else
                    {
                        // parse the name
                        switch (c)
                        {
                            case ' ':
                            case '\t':
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
                                if (tokenstart < 0)
                                    tokenstart = i;
                                tokenend = i;
                                continue;
                        }
                    }
                }
            }

            if (cookieName != null)
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
}
