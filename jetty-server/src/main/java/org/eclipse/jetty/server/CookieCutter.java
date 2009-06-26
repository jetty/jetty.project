// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;


/* ------------------------------------------------------------ */
/** Cookie parser
 * <p>Optimized stateful cookie parser.  Cookies fields are added with the
 * {@link #addCookieField(String)} method and parsed on the next subsequent
 * call to {@link #getCookies()}.
 * If the added fields are identical to those last added (as strings), then the 
 * cookies are not re parsed.
 * 
 *
 */
public class CookieCutter
{
    private static final byte STATE_DELIMITER = 1;
    private static final byte STATE_NAME = 2;
    private static final byte STATE_VALUE = 4;
    private static final byte STATE_QUOTED_VALUE = 8;
    private static final byte STATE_UNQUOTED_VALUE = 16;

    private Cookie[] _cookies;
    private Cookie[] _lastCookies;
    Object _lazyFields;
    int _fields;
    
    public CookieCutter()
    {  
    }
    
    public Cookie[] getCookies()
    {
        if (_cookies!=null)
            return _cookies;
        
        if (_lastCookies!=null &&
            _lazyFields!=null &&
            _fields==LazyList.size(_lazyFields))
            _cookies=_lastCookies;
        else
            parseFields();
        _lastCookies=_cookies;
        return _cookies;
    }
    
    public void setCookies(Cookie[] cookies)
    {
        _cookies=cookies;
        _lastCookies=null;
        _lazyFields=null;
        _fields=0;
    }
    
    public void reset()
    {
        _cookies=null;
        _fields=0;
    }
    
    public void addCookieField(String f)
    {
        if (f==null)
            return;
        f=f.trim();
        if (f.length()==0)
            return;
            
        if (LazyList.size(_lazyFields)>_fields)
        {
            if (f.equals(LazyList.get(_lazyFields,_fields)))
            {
                _fields++;
                return;
            }
            
            while (LazyList.size(_lazyFields)>_fields)
                _lazyFields=LazyList.remove(_lazyFields,_fields);
        }
        _cookies=null;
        _lastCookies=null;
        _lazyFields=LazyList.add(_lazyFields,_fields++,f);
    }
    
    
    protected void parseFields()
    {
        _lastCookies=null;
        _cookies=null;
        
        Object cookies = null;

        int version = 0;

        // delete excess fields
        while (LazyList.size(_lazyFields)>_fields)
            _lazyFields=LazyList.remove(_lazyFields,_fields);
        
        // For each cookie field
        for (int f=0;f<_fields;f++)
        {
            String hdr = LazyList.get(_lazyFields,f);
            
            // Parse the header
            String name = null;
            String value = null;

            Cookie cookie = null;

            byte state = STATE_NAME;
            for (int i = 0, tokenstart = 0, length = hdr.length(); i < length; i++)
            {
                char c = hdr.charAt(i);
                switch (c)
                {
                    case ',':
                    case ';':
                        switch (state)
                        {
                            case STATE_DELIMITER:
                                state = STATE_NAME;
                                tokenstart = i + 1;
                                break;
                            case STATE_UNQUOTED_VALUE:
                                state = STATE_NAME;
                                value = hdr.substring(tokenstart, i).trim();
                                tokenstart = i + 1;
                                break;
                            case STATE_NAME:
                                name = hdr.substring(tokenstart, i);
                                value = "";
                                tokenstart = i + 1;
                                break;
                            case STATE_VALUE:
                                state = STATE_NAME;
                                value = "";
                                tokenstart = i + 1;
                                break;
                        }
                        break;
                    case '=':
                        switch (state)
                        {
                            case STATE_NAME:
                                state = STATE_VALUE;
                                name = hdr.substring(tokenstart, i);
                                tokenstart = i + 1;
                                break;
                            case STATE_VALUE:
                                state = STATE_UNQUOTED_VALUE;
                                tokenstart = i;
                                break;
                        }
                        break;
                    case '"':
                        switch (state)
                        {
                            case STATE_VALUE:
                                state = STATE_QUOTED_VALUE;
                                tokenstart = i + 1;
                                break;
                            case STATE_QUOTED_VALUE:
                                state = STATE_DELIMITER;
                                value = hdr.substring(tokenstart, i);
                                break;
                        }
                        break;
                    case ' ':
                    case '\t':
                        break;
                    default:
                        switch (state)
                        {
                            case STATE_VALUE:
                                state = STATE_UNQUOTED_VALUE;
                                tokenstart = i;
                                break;
                            case STATE_DELIMITER:
                                state = STATE_NAME;
                                tokenstart = i;
                                break;
                        }
                }

                if (i + 1 == length)
                {
                    switch (state)
                    {
                        case STATE_UNQUOTED_VALUE:
                            value = hdr.substring(tokenstart).trim();
                            break;
                        case STATE_NAME:
                            name = hdr.substring(tokenstart);
                            value = "";
                            break;
                        case STATE_VALUE:
                            value = "";
                            break;
                    }
                }

                if (name != null && value != null)
                {
                    name = name.trim();

                    try
                    {
                        if (name.startsWith("$"))
                        {
                            String lowercaseName = name.toLowerCase();
                            if ("$path".equals(lowercaseName))
                            {
                                cookie.setPath(value);
                            }
                            else if ("$domain".equals(lowercaseName))
                            {
                                cookie.setDomain(value);
                            }
                            else if ("$version".equals(lowercaseName))
                            {
                                version = Integer.parseInt(value);
                            }
                        }
                        else
                        {
                            cookie = new Cookie(name, value);

                            if (version > 0)
                            {
                                cookie.setVersion(version);
                            }

                            cookies = LazyList.add(cookies, cookie);
                        }
                    }
                    catch (Exception e)
                    {
                        Log.ignore(e);
                    }

                    name = null;
                    value = null;
                }
            }
        }

        _cookies = (Cookie[]) LazyList.toArray(cookies,Cookie.class);
        _lastCookies=_cookies;
    }
    
}
