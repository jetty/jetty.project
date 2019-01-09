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

package org.eclipse.jetty.websocket.servlet;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.servlet.internal.UpgradeHttpServletRequest;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Servlet specific Upgrade Request implementation.
 */
public class ServletUpgradeRequest
{
    private final URI requestURI;
    private final String queryString;
    private final UpgradeHttpServletRequest request;
    private final boolean secure;
    private final Negotiation negotiation;
    private List<HttpCookie> cookies;
    private Map<String, List<String>> parameterMap;

    public ServletUpgradeRequest(Negotiation negotiation) throws URISyntaxException
    {
        this.negotiation = negotiation;
        HttpServletRequest httpRequest = negotiation.getRequest();
        this.queryString = httpRequest.getQueryString();
        this.secure = httpRequest.isSecure();

        StringBuffer uri = httpRequest.getRequestURL();
        if (this.queryString != null)
            uri.append("?").append(this.queryString);
        uri.replace(0, uri.indexOf(":"), secure?"wss":"ws");
        this.requestURI = new URI(uri.toString());
        this.request = new UpgradeHttpServletRequest(httpRequest);
    }

    public X509Certificate[] getCertificates()
    {
        return (X509Certificate[])request.getAttribute("javax.servlet.request.X509Certificate");
    }

    public List<HttpCookie> getCookies()
    {
        if (cookies == null)
        {
            Cookie[] requestCookies = request.getCookies();
            if (requestCookies != null)
            {
                cookies = new ArrayList<>();
                for (Cookie requestCookie : requestCookies)
                {
                    HttpCookie cookie = new HttpCookie(requestCookie.getName(), requestCookie.getValue());
                    // No point handling domain/path/expires/secure/httponly on client request cookies
                    cookies.add(cookie);
                }
            }
        }

        return cookies;
    }

    public List<ExtensionConfig> getExtensions()
    {
        return negotiation.getOfferedExtensions();
    }

    public String getHeader(String name)
    {
        return request.getHeader(name);
    }

    public int getHeaderInt(String name)
    {
        String val = request.getHeader(name);
        if (val == null)
        {
            return -1;
        }
        return Integer.parseInt(val);
    }

    public Map<String, List<String>> getHeadersMap()
    {
        return request.getHeaders();
    }

    public List<String> getHeaders(String name)
    {
        return request.getHeaders().get(name);
    }

    public String getHost()
    {
        return requestURI.getHost();
    }

    public HttpServletRequest getHttpServletRequest()
    {
        return request;
    }

    public String getHttpVersion()
    {
        return request.getProtocol();
    }

    public Locale getLocale()
    {
        return request.getLocale();
    }

    public Enumeration<Locale> getLocales()
    {
        return request.getLocales();
    }

    public SocketAddress getLocalSocketAddress()
    {
        // TODO: fix when HttpServletRequest can use Unix Socket stuff
        return new InetSocketAddress(request.getLocalAddr(), request.getLocalPort());
    }

    public String getMethod()
    {
        return request.getMethod();
    }

    public String getOrigin()
    {
        return getHeader("Origin");
    }

    public Map<String, List<String>> getParameterMap()
    {
        if (parameterMap == null)
        {
            Map<String, String[]> requestParams = request.getParameterMap();
            if (requestParams != null)
            {
                parameterMap = new HashMap<>(requestParams.size());
                for (Map.Entry<String, String[]> entry : requestParams.entrySet())
                    parameterMap.put(entry.getKey(), Arrays.asList(entry.getValue()));
            }
        }
        return parameterMap;
    }

    public String getProtocolVersion()
    {
        String version = request.getHeader(HttpHeader.SEC_WEBSOCKET_VERSION.asString());
        if (version == null)
        {
            return Integer.toString(WebSocketConstants.SPEC_VERSION);
        }
        return version;
    }

    public String getQueryString()
    {
        return this.queryString;
    }

    public SocketAddress getRemoteSocketAddress()
    {
        return new InetSocketAddress(request.getRemoteAddr(), request.getRemotePort());
    }

    public String getRequestPath()
    {
        // Since this can be called from a filter, we need to be smart about determining the target request path.
        String contextPath = request.getContextPath();
        String requestPath = request.getRequestURI();
        if (requestPath.startsWith(contextPath))
            requestPath = requestPath.substring(contextPath.length());
        return requestPath;
    }

    public URI getRequestURI()
    {
        return requestURI;
    }

    public Object getServletAttribute(String name)
    {
        return request.getAttribute(name);
    }

    public Map<String, Object> getServletAttributes()
    {
        return request.getAttributes();
    }

    public Map<String, List<String>> getServletParameters()
    {
        return getParameterMap();
    }

    public HttpSession getSession()
    {
        return request.getSession(false);
    }

    public List<String> getSubProtocols()
    {
        return negotiation.getOfferedSubprotocols();
    }

    public Principal getUserPrincipal()
    {
        return request.getUserPrincipal();
    }

    public boolean hasSubProtocol(String test)
    {
        for (String protocol : getSubProtocols())
        {
            if (protocol.equalsIgnoreCase(test))
            {
                return true;
            }
        }
        return false;
    }

    public boolean isSecure()
    {
        return this.secure;
    }

    public boolean isUserInRole(String role)
    {
        return request.isUserInRole(role);
    }

    public void setServletAttribute(String name, Object value)
    {
        request.setAttribute(name, value);
    }
}
