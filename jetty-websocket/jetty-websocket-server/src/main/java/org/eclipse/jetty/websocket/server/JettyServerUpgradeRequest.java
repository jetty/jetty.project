//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server;

import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;

public class JettyServerUpgradeRequest
{
    private ServletUpgradeRequest upgradeRequest;

    JettyServerUpgradeRequest(ServletUpgradeRequest request)
    {
        upgradeRequest = request;
    }

    /**
     * @return The {@link X509Certificate} instance at request attribute "javax.servlet.request.X509Certificate" or null.
     */
    public X509Certificate[] getCertificates()
    {
        return upgradeRequest.getCertificates();
    }

    /**
     * @return Request cookies
     * @see HttpServletRequest#getCookies()
     */
    public List<HttpCookie> getCookies()
    {
        return upgradeRequest.getCookies();
    }

    /**
     * @return The extensions offered
     * @see Negotiation#getOfferedExtensions()
     */
    public List<ExtensionConfig> getExtensions()
    {
        return upgradeRequest.getExtensions().stream().map(JettyExtensionConfig::new).collect(Collectors.toList());
    }

    /**
     * @param name Header name
     * @return Header value or null
     * @see HttpServletRequest#getHeader(String)
     */
    public String getHeader(String name)
    {
        return upgradeRequest.getHeader(name);
    }

    /**
     * @param name Header name
     * @return Header value as integer or -1
     * @see HttpServletRequest#getHeader(String)
     */
    public int getHeaderInt(String name)
    {
        return upgradeRequest.getHeaderInt(name);
    }

    /**
     * @return Map of headers
     */
    public Map<String, List<String>> getHeadersMap()
    {
        return upgradeRequest.getHeadersMap();
    }

    /**
     * @param name Header name
     * @return List of header values or null
     */
    public List<String> getHeaders(String name)
    {
        return upgradeRequest.getHeaders(name);
    }

    /**
     * @return The requested host
     * @see HttpServletRequest#getRequestURL()
     */
    public String getHost()
    {
        return upgradeRequest.getHost();
    }

    /**
     * @return Immutable version of {@link HttpServletRequest}
     */
    public HttpServletRequest getHttpServletRequest()
    {
        return upgradeRequest.getHttpServletRequest();
    }

    /**
     * @return The HTTP protocol version
     * @see HttpServletRequest#getProtocol()
     */
    public String getHttpVersion()
    {
        return upgradeRequest.getHttpVersion();
    }

    /**
     * @return The requested Locale
     * @see HttpServletRequest#getLocale()
     */
    public Locale getLocale()
    {
        return upgradeRequest.getLocale();
    }

    /**
     * @return The requested Locales
     * @see HttpServletRequest#getLocales()
     */
    public Enumeration<Locale> getLocales()
    {
        return upgradeRequest.getLocales();
    }

    /**
     * @return The local requested address, which is typically an {@link InetSocketAddress}, but may be another derivation of {@link SocketAddress}
     * @see ServletRequest#getLocalAddr()
     * @see ServletRequest#getLocalPort()
     */
    public SocketAddress getLocalSocketAddress()
    {
        return upgradeRequest.getLocalSocketAddress();
    }

    /**
     * @return The requested method
     * @see HttpServletRequest#getMethod()
     */
    public String getMethod()
    {
        return upgradeRequest.getMethod();
    }

    /**
     * @return The origin header value
     */
    public String getOrigin()
    {
        return upgradeRequest.getOrigin();
    }

    /**
     * @return The request parameter map
     * @see ServletRequest#getParameterMap()
     */
    public Map<String, List<String>> getParameterMap()
    {
        return upgradeRequest.getParameterMap();
    }

    /**
     * @return WebSocket protocol version from "Sec-WebSocket-Version" header
     */
    public String getProtocolVersion()
    {
        return upgradeRequest.getProtocolVersion();
    }

    /**
     * @return The request query string
     * @see HttpServletRequest#getQueryString()
     */
    public String getQueryString()
    {
        return upgradeRequest.getQueryString();
    }

    /**
     * @return The remote request address, which is typically an {@link InetSocketAddress}, but may be another derivation of {@link SocketAddress}
     * @see ServletRequest#getRemoteAddr()
     * @see ServletRequest#getRemotePort()
     */
    public SocketAddress getRemoteSocketAddress()
    {
        return upgradeRequest.getRemoteSocketAddress();
    }

    /**
     * @return The request URI path within the context
     */
    public String getRequestPath()
    {
        return upgradeRequest.getRequestPath();
    }

    /**
     * @return The request URI
     * @see HttpServletRequest#getRequestURL()
     */
    public URI getRequestURI()
    {
        return upgradeRequest.getRequestURI();
    }

    /**
     * @param name Attribute name
     * @return Attribute value or null
     * @see ServletRequest#getAttribute(String)
     */
    public Object getServletAttribute(String name)
    {
        return upgradeRequest.getServletAttribute(name);
    }

    /**
     * @return Request attribute map
     */
    public Map<String, Object> getServletAttributes()
    {
        return upgradeRequest.getServletAttributes();
    }

    /**
     * @return Request parameters
     * @see ServletRequest#getParameterMap()
     */
    public Map<String, List<String>> getServletParameters()
    {
        return upgradeRequest.getServletParameters();
    }

    /**
     * @return The HttpSession, which may be null or invalidated
     * @see HttpServletRequest#getSession(boolean)
     */
    public HttpSession getSession()
    {
        return upgradeRequest.getSession();
    }

    /**
     * @return Get WebSocket negotiation offered sub protocols
     */
    public List<String> getSubProtocols()
    {
        return upgradeRequest.getSubProtocols();
    }

    /**
     * @return The User's {@link Principal} or null
     * @see HttpServletRequest#getUserPrincipal()
     */
    public Principal getUserPrincipal()
    {
        return upgradeRequest.getUserPrincipal();
    }

    /**
     * @param subprotocol A sub protocol name
     * @return True if the sub protocol was offered
     */
    public boolean hasSubProtocol(String subprotocol)
    {
        return upgradeRequest.hasSubProtocol(subprotocol);
    }

    /**
     * @return True if the request is secure
     * @see ServletRequest#isSecure()
     */
    public boolean isSecure()
    {
        return upgradeRequest.isSecure();
    }

    /**
     * @param role The user role
     * @return True if the requests user has the role
     * @see HttpServletRequest#isUserInRole(String)
     */
    public boolean isUserInRole(String role)
    {
        return upgradeRequest.isUserInRole(role);
    }

    /**
     * @param name Attribute name
     * @param value Attribute value to set
     * @see ServletRequest#setAttribute(String, Object)
     */
    public void setServletAttribute(String name, Object value)
    {
        upgradeRequest.setServletAttribute(name, value);
    }
}
