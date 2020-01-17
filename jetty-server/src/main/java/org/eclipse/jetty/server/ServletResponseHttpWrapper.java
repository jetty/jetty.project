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

import java.io.IOException;
import java.util.Collection;
import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

/**
 * ServletResponseHttpWrapper
 *
 * Wrapper to tunnel a ServletResponse via an HttpServletResponse
 */
public class ServletResponseHttpWrapper extends ServletResponseWrapper implements HttpServletResponse
{
    public ServletResponseHttpWrapper(ServletResponse response)
    {
        super(response);
    }

    @Override
    public void addCookie(Cookie cookie)
    {
    }

    @Override
    public boolean containsHeader(String name)
    {
        return false;
    }

    @Override
    public String encodeURL(String url)
    {
        return null;
    }

    @Override
    public String encodeRedirectURL(String url)
    {
        return null;
    }

    @Override
    public String encodeUrl(String url)
    {
        return null;
    }

    @Override
    public String encodeRedirectUrl(String url)
    {
        return null;
    }

    @Override
    public void sendError(int sc, String msg) throws IOException
    {
    }

    @Override
    public void sendError(int sc) throws IOException
    {
    }

    @Override
    public void sendRedirect(String location) throws IOException
    {
    }

    @Override
    public void setDateHeader(String name, long date)
    {
    }

    @Override
    public void addDateHeader(String name, long date)
    {
    }

    @Override
    public void setHeader(String name, String value)
    {
    }

    @Override
    public void addHeader(String name, String value)
    {
    }

    @Override
    public void setIntHeader(String name, int value)
    {
    }

    @Override
    public void addIntHeader(String name, int value)
    {
    }

    @Override
    public void setStatus(int sc)
    {
    }

    @Override
    public void setStatus(int sc, String sm)
    {
    }

    /**
     * @see javax.servlet.http.HttpServletResponse#getHeader(java.lang.String)
     */
    @Override
    public String getHeader(String name)
    {
        return null;
    }

    /**
     * @see javax.servlet.http.HttpServletResponse#getHeaderNames()
     */
    @Override
    public Collection<String> getHeaderNames()
    {
        return null;
    }

    /**
     * @see javax.servlet.http.HttpServletResponse#getHeaders(java.lang.String)
     */
    @Override
    public Collection<String> getHeaders(String name)
    {
        return null;
    }

    /**
     * @see javax.servlet.http.HttpServletResponse#getStatus()
     */
    @Override
    public int getStatus()
    {
        return 0;
    }
}
