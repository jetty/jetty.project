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
import java.security.Principal;
import java.util.Collection;
import java.util.Enumeration;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;

/**
 * ServletRequestHttpWrapper
 *
 * Class to tunnel a ServletRequest via an HttpServletRequest
 */
public class ServletRequestHttpWrapper extends ServletRequestWrapper implements HttpServletRequest
{
    public ServletRequestHttpWrapper(ServletRequest request)
    {
        super(request);
    }

    @Override
    public String getAuthType()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Cookie[] getCookies()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getDateHeader(String name)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getHeader(String name)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Enumeration<String> getHeaderNames()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Enumeration<String> getHeaders(String name)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getIntHeader(String name)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getMethod()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getPathInfo()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getPathTranslated()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getContextPath()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getQueryString()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getRemoteUser()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isUserInRole(String role)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Principal getUserPrincipal()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getRequestedSessionId()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getRequestURI()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public StringBuffer getRequestURL()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getServletPath()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpSession getSession(boolean create)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpSession getSession()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isRequestedSessionIdValid()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isRequestedSessionIdFromCookie()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isRequestedSessionIdFromURL()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void login(String username, String password) throws ServletException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void logout() throws ServletException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String changeSessionId()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException
    {
        throw new UnsupportedOperationException();
    }
}
