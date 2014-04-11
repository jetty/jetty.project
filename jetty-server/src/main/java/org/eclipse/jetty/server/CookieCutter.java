//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.Locale;

import javax.servlet.http.Cookie;

import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


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
    private static final Logger LOG = Log.getLogger(CookieCutter.class);


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

            boolean invalue=false;
            boolean quoted=false;
            boolean escaped=false;
            int tokenstart=-1;
            int tokenend=-1;
            for (int i = 0, length = hdr.length(), last=length-1; i < length; i++)
            {
                char c = hdr.charAt(i);
                
                // Handle quoted values for name or value
                if (quoted)
                {
                    if (escaped)
                    {
                        escaped=false;
                        continue;
                    }
                    
                    switch (c)
                    {
                        case '"':
                            tokenend=i;
                            quoted=false;

                            // handle quote as last character specially
                            if (i==last)
                            {
                                if (invalue)
                                    value = hdr.substring(tokenstart, tokenend+1);
                                else
                                {
                                    name = hdr.substring(tokenstart, tokenend+1);
                                    value = "";
                                }
                            }
                            break;
                            
                        case '\\':
                            escaped=true;
                            continue;
                        default:
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
                                continue;
                                
                            case '"':
                                if (tokenstart<0)
                                {
                                    quoted=true;
                                    tokenstart=i;
                                }
                                tokenend=i;
                                if (i==last)
                                {
                                    value = hdr.substring(tokenstart, tokenend+1);
                                    break;
                                }
                                continue;

                            case ';':
                            // case ',':
                                if (tokenstart>=0)
                                    value = hdr.substring(tokenstart, tokenend+1);
                                else
                                    value="";
                                tokenstart = -1;
                                invalue=false;
                                break;
                                
                            default:
                                if (tokenstart<0)
                                    tokenstart=i;
                                tokenend=i;
                                if (i==last)
                                {
                                    value = hdr.substring(tokenstart, tokenend+1);
                                    break;
                                }
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
                                
                            case '"':
                                if (tokenstart<0)
                                {
                                    quoted=true;
                                    tokenstart=i;
                                }
                                tokenend=i;
                                if (i==last)
                                {
                                    name = hdr.substring(tokenstart, tokenend+1);
                                    value = "";
                                    break;
                                }
                                continue;

                            case ';':
                            // case ',':
                                if (tokenstart>=0)
                                {
                                    name = hdr.substring(tokenstart, tokenend+1);
                                    value = "";
                                }
                                tokenstart = -1;
                                break;

                            case '=':
                                if (tokenstart>=0)
                                    name = hdr.substring(tokenstart, tokenend+1);
                                tokenstart = -1;
                                invalue=true;
                                continue;
                                
                            default:
                                if (tokenstart<0)
                                    tokenstart=i;
                                tokenend=i;
                                if (i==last)
                                {
                                    name = hdr.substring(tokenstart, tokenend+1);
                                    value = "";
                                    break;
                                }
                                continue;
                        }
                    }
                }

                // If after processing the current character we have a value and a name, then it is a cookie
                if (value!=null && name!=null)
                {
                    // TODO handle unquoting during parsing!  But quoting is uncommon
                    name=QuotedStringTokenizer.unquoteOnly(name);
                    value=QuotedStringTokenizer.unquoteOnly(value);
                    
                    try
                    {
                        if (name.startsWith("$"))
                        {
                            String lowercaseName = name.toLowerCase(Locale.ENGLISH);
                            if ("$path".equals(lowercaseName))
                            {
                                if (cookie!=null)
                                    cookie.setPath(value);
                            }
                            else if ("$domain".equals(lowercaseName))
                            {
                                if (cookie!=null)
                                    cookie.setDomain(value);
                            }
                            else if ("$port".equals(lowercaseName))
                            {
                                if (cookie!=null)
                                    cookie.setComment("$port="+value);
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
                                cookie.setVersion(version);
                            cookies = LazyList.add(cookies, cookie);
                        }
                    }
                    catch (Exception e)
                    {
                        LOG.debug(e);
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
