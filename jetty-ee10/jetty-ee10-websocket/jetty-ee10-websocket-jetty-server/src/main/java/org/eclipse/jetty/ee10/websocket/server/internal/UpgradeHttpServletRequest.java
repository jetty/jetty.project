//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.server.internal;

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

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;

/**
 * An immutable, feature limited, HttpServletRequest that will not be recycled by Jetty.
 */
public class UpgradeHttpServletRequest implements HttpServletRequest
{
    private static final String UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE = "Feature unsupported after Upgraded to WebSocket";

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
    private final String requestId;
    private final String protocolRequestId;
    private final ServletConnection servletConnection;

    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, String[]> parameters = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, Object> attributes = new HashMap<>(2);
    private final List<Locale> locales = new ArrayList<>(2);

    private final HttpSession session;

    private final InetSocketAddress localAddress;
    private final String localName;
    private final InetSocketAddress remoteAddress;
    private final String remoteName;
    private final InetSocketAddress serverAddress;

    private boolean isAsyncStarted;
    private boolean isAsyncSupported;

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
        cookies = httpRequest.getCookies();
        session = httpRequest.getSession(false);
        requestId = httpRequest.getRequestId();
        protocolRequestId = httpRequest.getProtocolRequestId();
        servletConnection = httpRequest.getServletConnection();

        remoteUser = httpRequest.getRemoteUser();
        principal = httpRequest.getUserPrincipal();

        Enumeration<String> headerNames = httpRequest.getHeaderNames();
        while (headerNames.hasMoreElements())
        {
            String name = headerNames.nextElement();
            headers.put(name, Collections.list(httpRequest.getHeaders(name)));
        }

        parameters.putAll(httpRequest.getParameterMap());

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

    public void upgrade()
    {
        Enumeration<String> attributeNames = request.getAttributeNames();
        while (attributeNames.hasMoreElements())
        {
            String name = attributeNames.nextElement();
            attributes.put(name, request.getAttribute(name));
        }

        this.isAsyncStarted = request.isAsyncStarted();
        this.isAsyncSupported = request.isAsyncSupported();
        request = null;
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

    public Map<String, List<String>> getHeaders()
    {
        return Collections.unmodifiableMap(headers);
    }

    @Override
    public Enumeration<String> getHeaderNames()
    {
        return Collections.enumeration(headers.keySet());
    }

    @Override
    public long getDateHeader(String name)
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.getDateHeader(name);
    }

    @Override
    public int getIntHeader(String name)
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.getIntHeader(name);
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
        // TODO:
        return false;
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
        if (create && (session == null))
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return session;
    }

    @Override
    public HttpSession getSession()
    {
        return session;
    }

    @Override
    public String getRequestedSessionId()
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.getRequestedSessionId();
    }

    @Override
    public boolean isRequestedSessionIdValid()
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.isRequestedSessionIdValid();
    }

    @Override
    public boolean isRequestedSessionIdFromCookie()
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.isRequestedSessionIdFromCookie();
    }

    @Override
    public boolean isRequestedSessionIdFromURL()
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.isRequestedSessionIdFromURL();
    }

    @Override
    public Object getAttribute(String name)
    {
        if (request == null)
            return attributes.get(name);
        return request.getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames()
    {
        if (request == null)
            return Collections.enumeration(attributes.keySet());
        return request.getAttributeNames();
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
        if (request == null)
            attributes.put(name, value);
        else
            request.setAttribute(name, value);
    }

    @Override
    public void removeAttribute(String name)
    {
        if (request == null)
            attributes.remove(name);
        else
            request.removeAttribute(name);
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
    public String getRequestId()
    {
        return requestId;
    }

    @Override
    public String getProtocolRequestId()
    {
        return protocolRequestId;
    }

    @Override
    public ServletConnection getServletConnection()
    {
        return servletConnection;
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.authenticate(response);
    }

    @Override
    public String changeSessionId()
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.changeSessionId();
    }

    @Override
    public AsyncContext getAsyncContext()
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.getAsyncContext();
    }

    @Override
    public String getCharacterEncoding()
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.getCharacterEncoding();
    }

    @Override
    public int getContentLength()
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.getContentLength();
    }

    @Override
    public long getContentLengthLong()
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.getContentLengthLong();
    }

    @Override
    public String getContentType()
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.getContentType();
    }

    @Override
    public ServletInputStream getInputStream() throws IOException
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.getInputStream();
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.getPart(name);
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.getParts();
    }

    @Override
    public BufferedReader getReader() throws IOException
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.getReader();
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path)
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.getRequestDispatcher(path);
    }

    @Override
    public boolean isAsyncStarted()
    {
        if (request == null)
            return isAsyncStarted;
        return request.isAsyncStarted();
    }

    @Override
    public boolean isAsyncSupported()
    {
        if (request == null)
            return isAsyncSupported;
        return request.isAsyncSupported();
    }

    @Override
    public void login(String username, String password) throws ServletException
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        request.login(username, password);
    }

    @Override
    public void logout() throws ServletException
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        request.logout();
    }

    @Override
    public void setCharacterEncoding(String enc) throws UnsupportedEncodingException
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        request.setCharacterEncoding(enc);
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.startAsync();
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.startAsync(servletRequest, servletResponse);
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException
    {
        if (request == null)
            throw new UnsupportedOperationException(UNSUPPORTED_AFTER_WEBSOCKET_UPGRADE);
        return request.upgrade(handlerClass);
    }
}
