//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;

public class UpgradeHttpServletRequest implements HttpServletRequest
{
    private static final String UNSUPPORTED_WITH_WEBSOCKET_UPGRADE = "Feature unsupported with a Upgraded to WebSocket HttpServletRequest";

    private HttpServletRequest request;
    private final ServletContext context;
    private final DispatcherType dispatcher;
    private final String method;
    private final String protocol;
    private final String scheme;
    private final boolean secure;
    private final String requestURI;
    private final StringBuffer requestURL;
    private final String pathInfo;
    private final String pathTranslated;
    private final String servletPath;
    private final String query;
    private final String authType;
    private final Cookie[] cookies;
    private final String remoteUser;
    private final Principal principal;

    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, String[]> parameters = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, Object> attributes = new HashMap<>(2);
    private final List<Locale> locales = new ArrayList<>(2);

    private HttpSession session;

    private final InetSocketAddress localAddress;
    private final String localName;
    private final InetSocketAddress remoteAddress;
    private final String remoteName;
    private final InetSocketAddress serverAddress;

    public UpgradeHttpServletRequest(HttpServletRequest httpRequest)
    {
        // The original request object must be held temporarily for the duration of the handshake
        // in order to be able to implement methods such as isUserInRole() and setAttribute().
        request = httpRequest;
        context = httpRequest.getServletContext();
        dispatcher = httpRequest.getDispatcherType();

        method = httpRequest.getMethod();
        protocol = httpRequest.getProtocol();
        scheme = httpRequest.getScheme();
        secure = httpRequest.isSecure();
        requestURI = httpRequest.getRequestURI();
        requestURL = httpRequest.getRequestURL();
        pathInfo = httpRequest.getPathInfo();
        pathTranslated = httpRequest.getPathTranslated();
        servletPath = httpRequest.getServletPath();
        query = httpRequest.getQueryString();
        authType = httpRequest.getAuthType();
        cookies = request.getCookies();

        remoteUser = httpRequest.getRemoteUser();
        principal = httpRequest.getUserPrincipal();

        Enumeration<String> headerNames = httpRequest.getHeaderNames();
        while (headerNames.hasMoreElements())
        {
            String name = headerNames.nextElement();
            headers.put(name, Collections.list(httpRequest.getHeaders(name)));
        }

        parameters.putAll(request.getParameterMap());

        Enumeration<String> attributeNames = httpRequest.getAttributeNames();
        while (attributeNames.hasMoreElements())
        {
            String name = attributeNames.nextElement();
            attributes.put(name, httpRequest.getAttribute(name));
        }

        Enumeration<Locale> localeElements = httpRequest.getLocales();
        while (localeElements.hasMoreElements())
        {
            locales.add(localeElements.nextElement());
        }

        localAddress = InetSocketAddress.createUnresolved(httpRequest.getLocalAddr(), httpRequest.getLocalPort());
        localName = httpRequest.getLocalName();
        remoteAddress = InetSocketAddress.createUnresolved(httpRequest.getRemoteAddr(), httpRequest.getRemotePort());
        remoteName = httpRequest.getRemoteHost();
        serverAddress = InetSocketAddress.createUnresolved(httpRequest.getServerName(), httpRequest.getServerPort());
    }

    public HttpServletRequest getHttpServletRequest()
    {
        return request;
    }

    @Override
    public String getAuthType()
    {
        return authType;
    }

    @Override
    public Cookie[] getCookies()
    {
        return cookies;
    }

    @Override
    public String getHeader(String name)
    {
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty())
            return null;
        return values.get(0);
    }

    @Override
    public Enumeration<String> getHeaders(String name)
    {
        List<String> values = headers.get(name);
        if (values == null)
            return Collections.emptyEnumeration();
        return Collections.enumeration(values);
    }

    @Override
    public Enumeration<String> getHeaderNames()
    {
        return Collections.enumeration(headers.keySet());
    }

    public Map<String, List<String>> getHeaders()
    {
        return Collections.unmodifiableMap(headers);
    }

    @Override
    public long getDateHeader(String name)
    {
        // TODO
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public int getIntHeader(String name)
    {
        // TODO
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public String getMethod()
    {
        return method;
    }

    @Override
    public String getPathInfo()
    {
        return pathInfo;
    }

    @Override
    public String getPathTranslated()
    {
        return pathTranslated;
    }

    @Override
    public String getContextPath()
    {
        return context.getContextPath();
    }

    @Override
    public String getQueryString()
    {
        return query;
    }

    @Override
    public String getRemoteUser()
    {
        return remoteUser;
    }

    @Override
    public boolean isUserInRole(String role)
    {
        HttpServletRequest request = getHttpServletRequest();
        if (request != null)
            return request.isUserInRole(role);
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public Principal getUserPrincipal()
    {
        return principal;
    }

    @Override
    public String getRequestURI()
    {
        return requestURI;
    }

    @Override
    public StringBuffer getRequestURL()
    {
        return requestURL;
    }

    @Override
    public String getServletPath()
    {
        return servletPath;
    }

    @Override
    public HttpSession getSession(boolean create)
    {
        HttpServletRequest request = getHttpServletRequest();
        if (request != null)
            return session = request.getSession(create);
        return session;
    }

    @Override
    public HttpSession getSession()
    {
        return getSession(true);
    }

    @Override
    public String getRequestedSessionId()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public boolean isRequestedSessionIdValid()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public boolean isRequestedSessionIdFromCookie()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public boolean isRequestedSessionIdFromURL()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public boolean isRequestedSessionIdFromUrl()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public Object getAttribute(String name)
    {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames()
    {
        return Collections.enumeration(attributes.keySet());
    }

    public Map<String, Object> getAttributes()
    {
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public String getParameter(String name)
    {
        String[] values = parameters.get(name);
        if (values == null || values.length == 0)
            return null;
        return values[0];
    }

    @Override
    public Enumeration<String> getParameterNames()
    {
        return Collections.enumeration(parameters.keySet());
    }

    @Override
    public String[] getParameterValues(String name)
    {
        return parameters.get(name);
    }

    @Override
    public Map<String, String[]> getParameterMap()
    {
        return parameters;
    }

    @Override
    public String getProtocol()
    {
        return protocol;
    }

    @Override
    public String getScheme()
    {
        return scheme;
    }

    @Override
    public String getServerName()
    {
        return serverAddress.getHostString();
    }

    @Override
    public int getServerPort()
    {
        return serverAddress.getPort();
    }

    @Override
    public String getRemoteAddr()
    {
        return remoteAddress.getHostString();
    }

    @Override
    public int getRemotePort()
    {
        return remoteAddress.getPort();
    }

    @Override
    public String getRemoteHost()
    {
        return remoteName;
    }

    @Override
    public void setAttribute(String name, Object value)
    {
        HttpServletRequest request = getHttpServletRequest();
        if (request != null)
            request.setAttribute(name, value);
        attributes.put(name, value);
    }

    @Override
    public void removeAttribute(String name)
    {
        HttpServletRequest request = getHttpServletRequest();
        if (request != null)
            request.removeAttribute(name);
        attributes.remove(name);
    }

    @Override
    public Locale getLocale()
    {
        if (locales.isEmpty())
            return Locale.getDefault();
        return locales.get(0);
    }

    @Override
    public Enumeration<Locale> getLocales()
    {
        return Collections.enumeration(locales);
    }

    @Override
    public boolean isSecure()
    {
        return secure;
    }

    @Override
    public String getRealPath(String path)
    {
        return context.getRealPath(path);
    }

    @Override
    public String getLocalName()
    {
        return localName;
    }

    @Override
    public String getLocalAddr()
    {
        return localAddress.getHostString();
    }

    @Override
    public int getLocalPort()
    {
        return localAddress.getPort();
    }

    @Override
    public ServletContext getServletContext()
    {
        return context;
    }

    @Override
    public DispatcherType getDispatcherType()
    {
        return dispatcher;
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public String changeSessionId()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public AsyncContext getAsyncContext()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public String getCharacterEncoding()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public int getContentLength()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public long getContentLengthLong()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public String getContentType()
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public BufferedReader getReader() throws IOException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path)
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public boolean isAsyncStarted()
    {
        return false;
    }

    @Override
    public boolean isAsyncSupported()
    {
        return false;
    }

    @Override
    public void login(String username, String password) throws ServletException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public void logout() throws ServletException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public void setCharacterEncoding(String enc) throws UnsupportedEncodingException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException
    {
        throw new UnsupportedOperationException(UNSUPPORTED_WITH_WEBSOCKET_UPGRADE);
    }

    public void complete()
    {
        request = null;
    }
}
