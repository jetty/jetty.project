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

package org.eclipse.jetty.websocket.server.internal;

import java.net.HttpCookie;
import java.net.URI;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;

class UpgradeRequestDelegate implements UpgradeRequest
{
    private final ServerUpgradeRequest request;

    UpgradeRequestDelegate(ServerUpgradeRequest request)
    {
        this.request = request;
    }

    @Override
    public List<HttpCookie> getCookies()
    {
        return Request.getCookies(request).stream()
            .map(org.eclipse.jetty.http.HttpCookie::asJavaNetHttpCookie)
            .toList();
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return request.getExtensions().stream()
            .map(JettyExtensionConfig::new)
            .collect(Collectors.toList());
    }

    @Override
    public String getHeader(String name)
    {
        return request.getHeaders().get(name);
    }

    @Override
    public int getHeaderInt(String name)
    {
        return (int)request.getHeaders().getLongField(name);
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        Map<String, List<String>> result = new LinkedHashMap<>();
        HttpFields headers = request.getHeaders();
        for (HttpField header : headers)
        {
            String name = header.getName();
            result.put(name, headers.getValuesList(name));
        }
        return result;
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return request.getHeaders().getValuesList(name);
    }

    @Override
    public String getHost()
    {
        return request.getHttpURI().getHost();
    }

    @Override
    public String getHttpVersion()
    {
        return request.getConnectionMetaData().getHttpVersion().asString();
    }

    @Override
    public String getMethod()
    {
        return request.getMethod();
    }

    @Override
    public String getOrigin()
    {
        return request.getHeaders().get(HttpHeader.ORIGIN);
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Fields fields = Request.extractQueryParameters(request);
        for (Fields.Field field : fields)
        {
            result.put(field.getName(), field.getValues());
        }
        return result;
    }

    @Override
    public String getProtocolVersion()
    {
        return request.getProtocolVersion();
    }

    @Override
    public String getQueryString()
    {
        return request.getHttpURI().getQuery();
    }

    @Override
    public URI getRequestURI()
    {
        HttpURI httpURI = request.getHttpURI();
        HttpURI.Mutable wsURI = HttpURI.build(httpURI);
        wsURI.scheme(HttpScheme.isSecure(httpURI.getScheme()) ? HttpScheme.WSS : HttpScheme.WS);
        return wsURI.toURI();
    }

    @Override
    public List<String> getSubProtocols()
    {
        return request.getSubProtocols();
    }

    @Override
    public Principal getUserPrincipal()
    {
        // TODO: no Principal concept in Jetty core.
        return null;
    }

    @Override
    public boolean hasSubProtocol(String subProtocol)
    {
        return request.hasSubProtocol(subProtocol);
    }

    @Override
    public boolean isSecure()
    {
        return request.isSecure();
    }
}
