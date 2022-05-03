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

package org.eclipse.jetty.websocket.core.server;

import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.server.internal.UpgradeHttpServletRequest;

/**
 * Upgrade request used for websocket negotiation.
 * Provides getters for things like the requested extensions and subprotocols so that the headers don't have to be parsed manually.
 */
public class ServerUpgradeRequest
{
    private final URI requestURI;
    private final String queryString;
    private final HttpServletRequest request;
    private final boolean secure;
    private final WebSocketNegotiation negotiation;
    private List<HttpCookie> cookies;
    private Map<String, List<String>> parameterMap;

    public ServerUpgradeRequest(WebSocketNegotiation negotiation) throws BadMessageException
    {
        this.negotiation = negotiation;
        this.request = negotiation.getRequest();
        this.queryString = request.getQueryString();
        this.secure = request.isSecure();

        try
        {
            StringBuffer uri = request.getRequestURL();
            if (this.queryString != null)
                uri.append("?").append(this.queryString);
            uri.replace(0, uri.indexOf(":"), secure ? "wss" : "ws");
            this.requestURI = new URI(uri.toString());
        }
        catch (Throwable t)
        {
            throw new BadMessageException("Bad WebSocket UpgradeRequest", t);
        }
    }

    /**
     * @return The {@link X509Certificate} instance at request attribute "jakarta.servlet.request.X509Certificate" or null.
     */
    public X509Certificate[] getCertificates()
    {
        return (X509Certificate[])request.getAttribute("jakarta.servlet.request.X509Certificate");
    }

    /**
     * @return Request cookies
     * @see HttpServletRequest#getCookies()
     */
    public List<HttpCookie> getCookies()
    {
        if (cookies == null)
        {
            Cookie[] reqCookies = request.getCookies();
            if (reqCookies != null)
            {
                cookies = Arrays.stream(reqCookies)
                    .map(c -> new HttpCookie(c.getName(), c.getValue()))
                    .collect(Collectors.toList());
            }
            else
            {
                cookies = Collections.emptyList();
            }
        }

        return cookies;
    }

    /**
     * @return The extensions offered
     * @see WebSocketNegotiation#getOfferedExtensions()
     */
    public List<ExtensionConfig> getExtensions()
    {
        return negotiation.getOfferedExtensions();
    }

    /**
     * @param name Header name
     * @return Header value or null
     * @see HttpServletRequest#getHeader(String)
     */
    public String getHeader(String name)
    {
        return request.getHeader(name);
    }

    /**
     * @param name Header name
     * @return Header value as integer or -1
     * @see HttpServletRequest#getHeader(String)
     */
    public int getHeaderInt(String name)
    {
        return request.getIntHeader(name);
    }

    /**
     * @return Map of headers
     * @see UpgradeHttpServletRequest#getHeaders()
     */
    public Map<String, List<String>> getHeadersMap()
    {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null)
        {
            while (headerNames.hasMoreElements())
            {
                String name = headerNames.nextElement();
                headers.put(name, Collections.list(request.getHeaders(name)));
            }
        }
        return headers;
    }

    /**
     * @param name Header name
     * @return List of header values or null
     * @see UpgradeHttpServletRequest#getHeaders()
     */
    public List<String> getHeaders(String name)
    {
        Enumeration<String> headers = request.getHeaders(name);
        if (headers == null || !headers.hasMoreElements())
            return null;
        return Collections.list(headers);
    }

    /**
     * @return The requested host
     * @see HttpServletRequest#getRequestURL()
     */
    public String getHost()
    {
        return requestURI.getHost();
    }

    /**
     * @return Immutable version of {@link HttpServletRequest}
     */
    public HttpServletRequest getHttpServletRequest()
    {
        return request;
    }

    /**
     * @return The HTTP protocol version
     * @see HttpServletRequest#getProtocol()
     */
    public String getHttpVersion()
    {
        return request.getProtocol();
    }

    /**
     * @return The requested Locale
     * @see HttpServletRequest#getLocale()
     */
    public Locale getLocale()
    {
        return request.getLocale();
    }

    /**
     * @return The requested Locales
     * @see HttpServletRequest#getLocales()
     */
    public Enumeration<Locale> getLocales()
    {
        return request.getLocales();
    }

    /**
     * @return The local requested address, which is typically an {@link InetSocketAddress}, but may be another derivation of {@link SocketAddress}
     * @see ServletRequest#getLocalAddr()
     * @see ServletRequest#getLocalPort()
     */
    public SocketAddress getLocalSocketAddress()
    {
        // TODO: fix when HttpServletRequest can use Unix Socket stuff.
        return new InetSocketAddress(request.getLocalAddr(), request.getLocalPort());
    }

    /**
     * @return The requested method
     * @see HttpServletRequest#getMethod()
     */
    public String getMethod()
    {
        return request.getMethod();
    }

    /**
     * @return The origin header value
     */
    public String getOrigin()
    {
        return getHeader("Origin");
    }

    /**
     * @return The request parameter map
     * @see ServletRequest#getParameterMap()
     */
    public Map<String, List<String>> getParameterMap()
    {
        if (parameterMap == null)
        {
            Map<String, String[]> requestParams = request.getParameterMap();
            if (requestParams != null)
            {
                parameterMap = new HashMap<>(requestParams.size());
                for (Map.Entry<String, String[]> entry : requestParams.entrySet())
                {
                    parameterMap.put(entry.getKey(), Arrays.asList(entry.getValue()));
                }
            }
        }
        return parameterMap;
    }

    /**
     * @return WebSocket protocol version from "Sec-WebSocket-Version" header
     */
    public String getProtocolVersion()
    {
        String version = request.getHeader(HttpHeader.SEC_WEBSOCKET_VERSION.asString());
        if (version == null)
        {
            return Integer.toString(WebSocketConstants.SPEC_VERSION);
        }
        return version;
    }

    /**
     * @return The request query string
     * @see HttpServletRequest#getQueryString()
     */
    public String getQueryString()
    {
        return this.queryString;
    }

    /**
     * @return The remote request address, which is typically an {@link InetSocketAddress}, but may be another derivation of {@link SocketAddress}
     * @see ServletRequest#getRemoteAddr()
     * @see ServletRequest#getRemotePort()
     */
    public SocketAddress getRemoteSocketAddress()
    {
        return new InetSocketAddress(request.getRemoteAddr(), request.getRemotePort());
    }

    /**
     * @return The request URI path within the context
     */
    public String getRequestPath()
    {
        // Since this can be called from a filter, we need to be smart about determining the target request path.
        // TODO probably better adding servletPath and pathInfo
        String contextPath = request.getContextPath();
        String requestPath = request.getRequestURI();
        if (requestPath.startsWith(contextPath))
            requestPath = requestPath.substring(contextPath.length());
        return requestPath;
    }

    /**
     * @return The request URI
     * @see HttpServletRequest#getRequestURL()
     */
    public URI getRequestURI()
    {
        return requestURI;
    }

    /**
     * @return the path within the context, combination of the ServletPath with the PathInfo.
     */
    public String getPathInContext()
    {
        return URIUtil.addPaths(request.getServletPath(), request.getPathInfo());
    }

    /**
     * @param name Attribute name
     * @return Attribute value or null
     * @see ServletRequest#getAttribute(String)
     */
    public Object getServletAttribute(String name)
    {
        return request.getAttribute(name);
    }

    /**
     * @return Request attribute map
     */
    public Map<String, Object> getServletAttributes()
    {
        Map<String, Object> attributes = new HashMap<>(2);
        Enumeration<String> attributeNames = request.getAttributeNames();
        while (attributeNames.hasMoreElements())
        {
            String name = attributeNames.nextElement();
            attributes.put(name, request.getAttribute(name));
        }
        return attributes;
    }

    /**
     * @return Request parameters
     * @see ServletRequest#getParameterMap()
     */
    public Map<String, List<String>> getServletParameters()
    {
        return getParameterMap();
    }

    /**
     * @return The HttpSession, which may be null or invalidated
     * @see HttpServletRequest#getSession(boolean)
     */
    public HttpSession getSession()
    {
        return request.getSession(false);
    }

    /**
     * @return Get WebSocket negotiation offered sub protocols
     */
    public List<String> getSubProtocols()
    {
        return negotiation.getOfferedSubprotocols();
    }

    /**
     * @return The User's {@link Principal} or null
     * @see HttpServletRequest#getUserPrincipal()
     */
    public Principal getUserPrincipal()
    {
        return request.getUserPrincipal();
    }

    /**
     * @param subprotocol A sub protocol name
     * @return True if the sub protocol was offered
     */
    public boolean hasSubProtocol(String subprotocol)
    {
        for (String protocol : getSubProtocols())
        {
            if (protocol.equalsIgnoreCase(subprotocol))
                return true;
        }
        return false;
    }

    /**
     * @return True if the request is secure
     * @see ServletRequest#isSecure()
     */
    public boolean isSecure()
    {
        return this.secure;
    }

    /**
     * @param role The user role
     * @return True if the requests user has the role
     * @see HttpServletRequest#isUserInRole(String)
     */
    public boolean isUserInRole(String role)
    {
        return request.isUserInRole(role);
    }

    /**
     * @param name Attribute name
     * @param value Attribute value to set
     * @see ServletRequest#setAttribute(String, Object)
     */
    public void setServletAttribute(String name, Object value)
    {
        request.setAttribute(name, value);
    }
}
