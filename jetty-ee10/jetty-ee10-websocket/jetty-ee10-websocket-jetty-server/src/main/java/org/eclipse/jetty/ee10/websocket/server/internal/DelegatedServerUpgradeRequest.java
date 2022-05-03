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

import java.net.HttpCookie;
import java.net.SocketAddress;
import java.net.URI;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.ee10.websocket.api.ExtensionConfig;
import org.eclipse.jetty.ee10.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;

public class DelegatedServerUpgradeRequest implements JettyServerUpgradeRequest
{
    private final ServerUpgradeRequest upgradeRequest;
    private final HttpServletRequest httpServletRequest;
    private List<HttpCookie> cookies;
    private Map<String, List<String>> parameterMap;

    public DelegatedServerUpgradeRequest(ServerUpgradeRequest request)
    {
        upgradeRequest = request;
        ServletContextRequest servletContextRequest = Request.as(upgradeRequest, ServletContextRequest.class);
        this.httpServletRequest = servletContextRequest.getHttpServletRequest();
    }

    @Override
    public List<HttpCookie> getCookies()
    {
        if (cookies == null)
        {
            Cookie[] reqCookies = httpServletRequest.getCookies();
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

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return upgradeRequest.getExtensions().stream()
            .map(JettyExtensionConfig::new)
            .collect(Collectors.toList());
    }

    @Override
    public String getHeader(String name)
    {
        return upgradeRequest.getHeaders().get(name);
    }

    @Override
    public int getHeaderInt(String name)
    {
        return httpServletRequest.getIntHeader(name);
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        Map<String, List<String>> headers = upgradeRequest.getHeaders().getFieldNamesCollection().stream()
            .collect(Collectors.toMap((name) -> name, (name) -> new ArrayList<>(getHeaders(name))));
        return Collections.unmodifiableMap(headers);
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return upgradeRequest.getHeaders().getValuesList(name);
    }

    @Override
    public String getHost()
    {
        return upgradeRequest.getHttpURI().getHost();
    }

    @Override
    public String getHttpVersion()
    {
        return upgradeRequest.getConnectionMetaData().getHttpVersion().asString();
    }

    @Override
    public String getMethod()
    {
        return upgradeRequest.getMethod();
    }

    @Override
    public String getOrigin()
    {
        return upgradeRequest.getHeaders().get(HttpHeader.ORIGIN);
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        if (parameterMap == null)
        {
            Map<String, String[]> requestParams = httpServletRequest.getParameterMap();
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

    @Override
    public String getProtocolVersion()
    {
        return upgradeRequest.getProtocolVersion();
    }

    @Override
    public String getQueryString()
    {
        return upgradeRequest.getHttpURI().getQuery();
    }

    @Override
    public URI getRequestURI()
    {
        return upgradeRequest.getHttpURI().toURI();
    }

    @Override
    public HttpSession getSession()
    {
        return httpServletRequest.getSession();
    }

    @Override
    public List<String> getSubProtocols()
    {
        return upgradeRequest.getSubProtocols();
    }

    @Override
    public Principal getUserPrincipal()
    {
        return httpServletRequest.getUserPrincipal();
    }

    @Override
    public boolean hasSubProtocol(String subprotocol)
    {
        return upgradeRequest.hasSubProtocol(subprotocol);
    }

    @Override
    public boolean isSecure()
    {
        return httpServletRequest.isSecure();
    }

    @Override
    public X509Certificate[] getCertificates()
    {
        return (X509Certificate[])httpServletRequest.getAttribute("jakarta.servlet.request.X509Certificate");
    }

    @Override
    public HttpServletRequest getHttpServletRequest()
    {
        return getHttpServletRequest();
    }

    @Override
    public Locale getLocale()
    {
        return httpServletRequest.getLocale();
    }

    @Override
    public Enumeration<Locale> getLocales()
    {
        return httpServletRequest.getLocales();
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return upgradeRequest.getConnectionMetaData().getLocalSocketAddress();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return upgradeRequest.getConnectionMetaData().getRemoteSocketAddress();
    }

    @Override
    public String getRequestPath()
    {
        return upgradeRequest.getPathInContext();
    }

    @Override
    public Object getServletAttribute(String name)
    {
        return upgradeRequest.getAttribute(name);
    }

    @Override
    public Map<String, Object> getServletAttributes()
    {
        Map<String, Object> attributes = new HashMap<>(2);
        Enumeration<String> attributeNames = httpServletRequest.getAttributeNames();
        while (attributeNames.hasMoreElements())
        {
            String name = attributeNames.nextElement();
            attributes.put(name, httpServletRequest.getAttribute(name));
        }
        return attributes;
    }

    @Override
    public Map<String, List<String>> getServletParameters()
    {
        return getParameterMap();
    }

    @Override
    public boolean isUserInRole(String role)
    {
        return httpServletRequest.isUserInRole(role);
    }

    @Override
    public void setServletAttribute(String name, Object value)
    {
        httpServletRequest.setAttribute(name, value);
    }
}
