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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.util.Collection;

import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;


/* ------------------------------------------------------------ */
/** 
 * ServletResponseHttpWrapper
 * 
 * Wrapper to tunnel a ServletResponse via a HttpServletResponse
 */
public class ServletResponseHttpWrapper extends ServletResponseWrapper implements HttpServletResponse
{
    public ServletResponseHttpWrapper(ServletResponse response)
    {
        super(response);
    }

    public void addCookie(Cookie cookie)
    {
    }

    public boolean containsHeader(String name)
    {
        return false;
    }

    public String encodeURL(String url)
    {
        return null;
    }

    public String encodeRedirectURL(String url)
    {
        return null;
    }

    public String encodeUrl(String url)
    {
        return null;
    }

    public String encodeRedirectUrl(String url)
    {
        return null;
    }

    public void sendError(int sc, String msg) throws IOException
    {
    }

    public void sendError(int sc) throws IOException
    {
    }

    public void sendRedirect(String location) throws IOException
    {
    }

    public void setDateHeader(String name, long date)
    {
    }

    public void addDateHeader(String name, long date)
    {
    }

    public void setHeader(String name, String value)
    {
    }

    public void addHeader(String name, String value)
    {
    }

    public void setIntHeader(String name, int value)
    {
    }

    public void addIntHeader(String name, int value)
    {
    }

    public void setStatus(int sc)
    {
    }

    public void setStatus(int sc, String sm)
    {
    }

    /**
     * @see javax.servlet.http.HttpServletResponse#getHeader(java.lang.String)
     */
    public String getHeader(String name)
    {
        return null;
    }

    /**
     * @see javax.servlet.http.HttpServletResponse#getHeaderNames()
     */
    public Collection<String> getHeaderNames()
    {
        return null;
    }

    /**
     * @see javax.servlet.http.HttpServletResponse#getHeaders(java.lang.String)
     */
    public Collection<String> getHeaders(String name)
    {
        return null;
    }

    /**
     * @see javax.servlet.http.HttpServletResponse#getStatus()
     */
    public int getStatus()
    {
        return 0;
    }

}
