//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.servlet.http.Cookie;

import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Cookie parser
 * <p>
 * Optimized stateful {@code Cookie} header parser.
 * Does not support {@code Set-Cookie} header parsing.
 * </p>
 * <p>
 * Cookies fields are added with the {@link #addCookieField(String)} method and
 * parsed on the next subsequent call to {@link #getCookies()}.
 * </p>
 * <p>
 * If the added fields are identical to those last added (as strings), then the
 * cookies are not re parsed.
 * </p>
 */
public class CookieCutter
{
    private static final Logger LOG = Log.getLogger(CookieCutter.class);

    private final CookieCompliance _compliance;
    private Cookie[] _cookies;
    private Cookie[] _lastCookies;
    private final List<String> _fieldList = new ArrayList<>();
    int _fields;

    public CookieCutter()
    {
        this(CookieCompliance.RFC6265);
    }

    public CookieCutter(CookieCompliance compliance)
    {
        _compliance = compliance;
    }

    public Cookie[] getCookies()
    {
        if (_cookies != null)
            return _cookies;

        if (_lastCookies != null && _fields == _fieldList.size())
            _cookies = _lastCookies;
        else
            parseFields();
        _lastCookies = _cookies;
        return _cookies;
    }

    public void setCookies(Cookie[] cookies)
    {
        _cookies = cookies;
        _lastCookies = null;
        _fieldList.clear();
        _fields = 0;
    }

    public void reset()
    {
        _cookies = null;
        _fields = 0;
    }

    public void addCookieField(String f)
    {
        if (f == null)
            return;
        f = f.trim();
        if (f.length() == 0)
            return;

        if (_fieldList.size() > _fields)
        {
            if (f.equals(_fieldList.get(_fields)))
            {
                _fields++;
                return;
            }

            while (_fieldList.size() > _fields)
            {
                _fieldList.remove(_fields);
            }
        }
        _cookies = null;
        _lastCookies = null;
        _fieldList.add(_fields++, f);
    }

    protected void parseFields()
    {
        _lastCookies = null;
        _cookies = null;

        List<Cookie> cookies = new ArrayList<>();

        int version = 0;

        // delete excess fields
        while (_fieldList.size() > _fields)
        {
            _fieldList.remove(_fields);
        }

        StringBuilder unquoted = null;

        // For each cookie field
        for (String hdr : _fieldList)
        {
            // Parse the header
            String name = null;

            Cookie cookie = null;

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
                        // parse the cookie-value
                        switch (c)
                        {
                            case ' ':
                            case '\t':
                                break;

                            case ',':
                                if (_compliance != CookieCompliance.RFC2965)
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
                                        if (_compliance == CookieCompliance.RFC2965)
                                        {
                                            String lowercaseName = name.toLowerCase(Locale.ENGLISH);
                                            switch (lowercaseName)
                                            {
                                                case "$path":
                                                    if (cookie != null)
                                                        cookie.setPath(value);
                                                    break;
                                                case "$domain":
                                                    if (cookie != null)
                                                        cookie.setDomain(value);
                                                    break;
                                                case "$port":
                                                    if (cookie != null)
                                                        cookie.setComment("$port=" + value);
                                                    break;
                                                case "$version":
                                                    version = Integer.parseInt(value);
                                                    break;
                                                default:
                                                    break;
                                            }
                                        }
                                    }
                                    else
                                    {
                                        cookie = new Cookie(name, value);
                                        if (version > 0)
                                            cookie.setVersion(version);
                                        if (!reject)
                                        {
                                            cookies.add(cookie);
                                        }
                                    }
                                }
                                catch (Exception e)
                                {
                                    LOG.debug(e);
                                }

                                name = null;
                                tokenstart = -1;
                                invalue = false;
                                reject = false;

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

                                if (_compliance == CookieCompliance.RFC6265)
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
                            case ' ':
                            case '\t':
                                continue;

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

                                if (_compliance == CookieCompliance.RFC6265)
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
        }

        _cookies = cookies.toArray(new Cookie[0]);
        _lastCookies = _cookies;
    }

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
