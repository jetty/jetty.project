//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

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
        return null;
    }

    @Override
    public Cookie[] getCookies()
    {
        return null;
    }

    @Override
    public long getDateHeader(String name)
    {
        return 0;
    }

    @Override
    public String getHeader(String name)
    {
        return null;
    }

    @Override
    public Enumeration<String> getHeaders(String name)
    {
        return null;
    }

    @Override
    public Enumeration<String> getHeaderNames()
    {
        return null;
    }

    @Override
    public int getIntHeader(String name)
    {
        return 0;
    }

    @Override
    public String getMethod()
    {
        return null;
    }

    @Override
    public String getPathInfo()
    {
        return null;
    }

    @Override
    public String getPathTranslated()
    {
        return null;
    }

    @Override
    public String getContextPath()
    {
        return null;
    }

    @Override
    public String getQueryString()
    {
        return null;
    }

    @Override
    public String getRemoteUser()
    {
        return null;
    }

    @Override
    public boolean isUserInRole(String role)
    {
        return false;
    }

    @Override
    public Principal getUserPrincipal()
    {
        return null;
    }

    @Override
    public String getRequestedSessionId()
    {
        return null;
    }

    @Override
    public String getRequestURI()
    {
        return null;
    }

    @Override
    public StringBuffer getRequestURL()
    {
        return null;
    }

    @Override
    public String getServletPath()
    {
        return null;
    }

    @Override
    public HttpSession getSession(boolean create)
    {
        return null;
    }

    @Override
    public HttpSession getSession()
    {
        return null;
    }

    @Override
    public boolean isRequestedSessionIdValid()
    {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromCookie()
    {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromURL()
    {
        return false;
    }

    @Override
    @Deprecated(since = "Servlet API 2.1")
    public boolean isRequestedSessionIdFromUrl()
    {
        return false;
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException
    {
        return false;
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException
    {
        return null;
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException
    {
        return null;
    }

    @Override
    public void login(String username, String password) throws ServletException
    {

    }

    @Override
    public void logout() throws ServletException
    {

    }

    @Override
    public String changeSessionId()
    {
        // TODO 3.1 Auto-generated method stub
        return null;
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException
    {
        // TODO 3.1 Auto-generated method stub
        return null;
    }
}
