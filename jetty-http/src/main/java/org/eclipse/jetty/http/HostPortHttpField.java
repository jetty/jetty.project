//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.StringUtil;



/* ------------------------------------------------------------ */
/**
 */
public class HostPortHttpField extends HttpField
{
    private final String _host;
    private final int _port;

    public HostPortHttpField(String authority)
    {
        this(HttpHeader.HOST,HttpHeader.HOST.asString(),authority);
    }
    
    public HostPortHttpField(HttpHeader header, String name, String authority)
    {
        super(header,name,authority);
        if (authority==null || authority.length()==0)
            throw new IllegalArgumentException("No Authority");
        try
        {
            if (authority.charAt(0)=='[')
            {
                // ipv6reference
                int close=authority.lastIndexOf(']');
                if (close<0)
                    throw new BadMessageException(HttpStatus.BAD_REQUEST_400,"Bad ipv6");
                _host=authority.substring(0,close+1);

                if (authority.length()>close+1)
                {
                    if (authority.charAt(close+1)!=':')
                        throw new BadMessageException(HttpStatus.BAD_REQUEST_400,"Bad ipv6 port");
                    _port=StringUtil.toInt(authority,close+2);
                }
                else
                    _port=0;
            }
            else
            {
                // ipv4address or hostname
                int c = authority.lastIndexOf(':');
                if (c>=0)
                {
                    _host=authority.substring(0,c);
                    _port=StringUtil.toInt(authority,c+1);
                }
                else
                {
                    _host=authority;
                    _port=0;
                }
            }
        }
        catch (BadMessageException bm)
        {
            throw bm;
        }
        catch(Exception e)
        {
            throw new BadMessageException(HttpStatus.BAD_REQUEST_400,"Bad HostPort",e);
        }
    }

    /* ------------------------------------------------------------ */
    /** Get the host.
     * @return the host
     */
    public String getHost()
    {
        return _host;
    }

    /* ------------------------------------------------------------ */
    /** Get the port.
     * @return the port
     */
    public int getPort()
    {
        return _port;
    }
}
