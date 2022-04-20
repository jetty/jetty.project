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
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee10.websocket.api.ExtensionConfig;
import org.eclipse.jetty.ee10.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;

public class DelegatedServerUpgradeRequest implements JettyServerUpgradeRequest
{
    private final ServerUpgradeRequest upgradeRequest;
    private List<HttpCookie> cookies;
    private Map<String, List<String>> parameterMap;

    public DelegatedServerUpgradeRequest(ServerUpgradeRequest request)
    {
        upgradeRequest = request;
    }

    @Override
    public List<HttpCookie> getCookies()
    {
        if (cookies == null)
        {
            List<org.eclipse.jetty.http.HttpCookie> reqCookies = Request.getCookies(upgradeRequest);
            if (reqCookies != null && !reqCookies.isEmpty())
            {
                cookies = reqCookies.stream()
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
        HttpField field = upgradeRequest.getHeaders().getField(name);
        return field == null ? -1 : field.getIntValue();
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
            Fields requestParams = Request.extractQueryParameters(upgradeRequest);
            parameterMap = new HashMap<>();
            for (String name : requestParams.getNames())
            {
                parameterMap.put(name, requestParams.getValues(name));
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
        // TODO:
        return null;
    }

    @Override
    public List<String> getSubProtocols()
    {
        return upgradeRequest.getSubProtocols();
    }

    @Override
    public Principal getUserPrincipal()
    {
        // TODO;
        return null;
    }

    @Override
    public boolean hasSubProtocol(String subprotocol)
    {
        return upgradeRequest.hasSubProtocol(subprotocol);
    }

    @Override
    public boolean isSecure()
    {
        return upgradeRequest.isSecure();
    }

    @Override
    public X509Certificate[] getCertificates()
    {
        return (X509Certificate[])upgradeRequest.getAttribute("jakarta.servlet.request.X509Certificate");
    }

    @Override
    public HttpServletRequest getHttpServletRequest()
    {
        // todo
        return null;
    }

    @Override
    public Locale getLocale()
    {
        return Request.getLocales(upgradeRequest).get(0);
    }

    @Override
    public Enumeration<Locale> getLocales()
    {
        return Collections.enumeration(Request.getLocales(upgradeRequest));
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
        Set<String> attributeNames = upgradeRequest.getAttributeNameSet();
        for (String name : attributeNames)
        {
            attributes.put(name, upgradeRequest.getAttribute(name));
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
        // TODO
        return false;
    }

    @Override
    public void setServletAttribute(String name, Object value)
    {
        upgradeRequest.setAttribute(name, value);
    }
}
