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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.util.Collection;

import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

/**
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
    public String getHeader(String name)
    {
        return null;
    }

    @Override
    public Collection<String> getHeaderNames()
    {
        return null;
    }

    @Override
    public Collection<String> getHeaders(String name)
    {
        return null;
    }

    @Override
    public int getStatus()
    {
        return 0;
    }
}
