//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

/** Cookie parser
 */
public abstract class CookieCutter
{
    protected static final Logger LOG = Log.getLogger(CookieCutter.class);

    protected final CookieCompliance _compliance;

    protected CookieCutter(CookieCompliance compliance)
    {
        _compliance = compliance;
    }

    protected void parseFields(List<String> rawFields)
    {
        StringBuilder unquoted=null;

        // For each cookie field
        for (String hdr : rawFields)
        {
            // Parse the header
            String name = null;
            String value = null;

            String cookieName = null;
            String cookieValue = null;
            String cookiePath = null;
            String cookieDomain = null;
            String cookieComment = null;
            int cookieVersion = 0;

            boolean invalue=false;
            boolean inQuoted=false;
            boolean quoted=false;
            boolean escaped=false;
            int tokenstart=-1;
            int tokenend=-1;
            for (int i = 0, length = hdr.length(), last=length-1; i < length; i++)
            {
                char c = hdr.charAt(i);

                // System.err.printf("i=%d c=%s v=%b q=%b e=%b u=%s s=%d e=%d%n" ,i,""+c,invalue,inQuoted,escaped,unquoted,tokenstart,tokenend);

                // Handle quoted values for name or value
                if (inQuoted)
                {
                    if (escaped)
                    {
                        escaped=false;
                        unquoted.append(c);
                        continue;
                    }

                    switch (c)
                    {
                        case '"':
                            inQuoted=false;
                            if (i==last)
                            {
                                value = unquoted.toString();
                                unquoted.setLength(0);
                            }
                            else
                            {
                                quoted=true;
                                tokenstart=i;
                                tokenend=-1;
                            }
                            break;

                        case '\\':
                            if (i==last)
                            {
                                unquoted.setLength(0);
                                inQuoted = false;
                                i--;
                            }
                            else
                            {
                                escaped=true;
                            }
                            continue;

                        default:
                            if (i==last)
                            {
                                // unterminated quote, let's ignore quotes
                                unquoted.setLength(0);
                                inQuoted = false;
                                i--;
                            }
                            else
                            {
                                unquoted.append(c);
                            }
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

                            case ';':
                                if (quoted)
                                {
                                    value = unquoted.toString();
                                    unquoted.setLength(0);
                                    quoted = false;
                                }
                                else if(tokenstart>=0 && tokenend>=0)
                                    value = hdr.substring(tokenstart, tokenend+1);
                                else
                                    value = "";

                                tokenstart = -1;
                                invalue=false;
                                break;

                            case '"':
                                if (tokenstart<0)
                                {
                                    tokenstart=i;
                                    inQuoted=true;
                                    if (unquoted==null)
                                        unquoted=new StringBuilder();
                                    break;
                                }
                                // fall through to default case

                            default:
                                if (quoted)
                                {
                                    // must have been a bad internal quote. let's fix as best we can
                                    unquoted.append(hdr.substring(tokenstart,i));
                                    inQuoted = true;
                                    quoted = false;
                                    i--;
                                    continue;
                                }
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

                            case ';':
                                if (quoted)
                                {
                                    name = unquoted.toString();
                                    unquoted.setLength(0);
                                    quoted = false;
                                }
                                else if(tokenstart>=0 && tokenend>=0)
                                {
                                    name = hdr.substring(tokenstart, tokenend+1);
                                }

                                tokenstart = -1;
                                break;

                            case '=':
                                if (quoted)
                                {
                                    name = unquoted.toString();
                                    unquoted.setLength(0);
                                    quoted = false;
                                }
                                else if(tokenstart>=0 && tokenend>=0)
                                {
                                    name = hdr.substring(tokenstart, tokenend+1);
                                }
                                tokenstart = -1;
                                invalue=true;
                                break;

                            default:
                                if (quoted)
                                {
                                    // must have been a bad internal quote. let's fix as best we can
                                    unquoted.append(hdr.substring(tokenstart,i));
                                    inQuoted = true;
                                    quoted = false;
                                    i--;
                                    continue;
                                }
                                if (tokenstart<0)
                                    tokenstart=i;
                                tokenend=i;
                                if (i==last)
                                    break;
                                continue;
                        }
                    }
                }

                if (invalue && i==last && value==null)
                {
                    if (quoted)
                    {
                        value = unquoted.toString();
                        unquoted.setLength(0);
                        quoted = false;
                    }
                    else if(tokenstart>=0 && tokenend>=0)
                    {
                        value = hdr.substring(tokenstart, tokenend+1);
                    }
                    else
                        value = "";
                }

                // If after processing the current character we have a value and a name, then it is a cookie
                if (name!=null && value!=null)
                {
                    try
                    {
                        if (name.startsWith("$"))
                        {
                            String lowercaseName = name.toLowerCase(Locale.ENGLISH);
                            if (_compliance==CookieCompliance.RFC6265)
                            {
                                // Ignore
                            }
                            else if ("$path".equals(lowercaseName))
                            {
                                cookiePath = value;
                            }
                            else if ("$domain".equals(lowercaseName))
                            {
                                cookieDomain = value;
                            }
                            else if ("$port".equals(lowercaseName))
                            {
                                cookieComment = (cookieComment==null?"$port=":", $port=")+value;
                            }
                            else if ("$version".equals(lowercaseName))
                            {
                                cookieVersion = Integer.parseInt(value);
                            }
                        }
                        else
                        {
                            if (cookieName!=null)
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
                    value = null;
                }
            }

            if (cookieName!=null)
                addCookie(cookieName,cookieValue,cookieDomain,cookiePath,cookieVersion,cookieComment);
        }
    }

    protected abstract void addCookie(String cookieName, String cookieValue, String cookieDomain, String cookiePath, int cookieVersion, String cookieComment);


}
