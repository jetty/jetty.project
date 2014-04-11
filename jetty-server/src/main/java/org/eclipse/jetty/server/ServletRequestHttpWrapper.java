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

import java.io.IOException;
import java.security.Principal;
import java.util.Collection;
import java.util.Enumeration;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;

/* ------------------------------------------------------------ */
/** Class to tunnel a ServletRequest via a HttpServletRequest
 */
public class ServletRequestHttpWrapper extends ServletRequestWrapper implements HttpServletRequest
{
    public ServletRequestHttpWrapper(ServletRequest request)
    {
        super(request);
    }

    public String getAuthType()
    {
        return null;
    }

    public Cookie[] getCookies()
    {
        return null;
    }

    public long getDateHeader(String name)
    {
        return 0;
    }

    public String getHeader(String name)
    {
        return null;
    }

    public Enumeration getHeaders(String name)
    {
        return null;
    }

    public Enumeration getHeaderNames()
    {
        return null;
    }

    public int getIntHeader(String name)
    {
        return 0;
    }

    public String getMethod()
    {
        return null;
    }

    public String getPathInfo()
    {
        return null;
    }

    public String getPathTranslated()
    {
        return null;
    }

    public String getContextPath()
    {
        return null;
    }

    public String getQueryString()
    {
        return null;
    }

    public String getRemoteUser()
    {
        return null;
    }

    public boolean isUserInRole(String role)
    {
        return false;
    }

    public Principal getUserPrincipal()
    {
        return null;
    }

    public String getRequestedSessionId()
    {
        return null;
    }

    public String getRequestURI()
    {
        return null;
    }

    public StringBuffer getRequestURL()
    {
        return null;
    }

    public String getServletPath()
    {
        return null;
    }

    public HttpSession getSession(boolean create)
    {
        return null;
    }

    public HttpSession getSession()
    {
        return null;
    }

    public boolean isRequestedSessionIdValid()
    {
        return false;
    }

    public boolean isRequestedSessionIdFromCookie()
    {
        return false;
    }

    public boolean isRequestedSessionIdFromURL()
    {
        return false;
    }

    public boolean isRequestedSessionIdFromUrl()
    {
        return false;
    }

    /** 
     * @see javax.servlet.http.HttpServletRequest#authenticate(javax.servlet.http.HttpServletResponse)
     */
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException
    {
        return false;
    }

    /** 
     * @see javax.servlet.http.HttpServletRequest#getPart(java.lang.String)
     */
    public Part getPart(String name) throws IOException, ServletException
    {
        return null;
    }

    /** 
     * @see javax.servlet.http.HttpServletRequest#getParts()
     */
    public Collection<Part> getParts() throws IOException, ServletException
    {
        return null;
    }

    /** 
     * @see javax.servlet.http.HttpServletRequest#login(java.lang.String, java.lang.String)
     */
    public void login(String username, String password) throws ServletException
    {

    }

    /** 
     * @see javax.servlet.http.HttpServletRequest#logout()
     */
    public void logout() throws ServletException
    {
        
    }

    
}
