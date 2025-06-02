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
import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;

class CompletedUpgradeRequest implements UpgradeRequest
{
    private final HttpFields _httpFields;
    private final List<HttpCookie> _cookies;
    private final List<ExtensionConfig> _extensions;
    private final HttpURI _httpURI;
    private final String _httpVersion;
    private final String _method;
    private final List<String> _subProtocols;
    private final Map<String, List<String>> _queryParams;
    private final boolean _secure;
    private final String _protocolVersion;

    CompletedUpgradeRequest(ServerUpgradeRequest request)
    {
        _httpFields = request.getHeaders().asImmutable();
        _httpURI = request.getHttpURI();
        _method = request.getMethod();
        _httpVersion = request.getConnectionMetaData().getHttpVersion().asString();
        _subProtocols = request.getSubProtocols();
        _secure = request.isSecure();
        _protocolVersion = request.getProtocolVersion();
        _cookies = Request.getCookies(request).stream()
            .map(org.eclipse.jetty.http.HttpCookie::asJavaNetHttpCookie)
            .toList();
        _extensions = request.getExtensions().stream()
            .map(JettyExtensionConfig::new)
            .collect(Collectors.toList());

        // Extract query parameters from the request
        _queryParams = new LinkedHashMap<>();
        Request.extractQueryParameters(request).forEach(f -> _queryParams.put(f.getName(), f.getValues()));
    }

    @Override
    public List<HttpCookie> getCookies()
    {
        return _cookies;
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return _extensions;
    }

    @Override
    public String getHeader(String name)
    {
        return _httpFields.get(name);
    }

    @Override
    public int getHeaderInt(String name)
    {
        HttpField field = _httpFields.getField(name);
        return field == null ? -1 : field.getIntValue();
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return HttpFields.asMap(_httpFields);
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return _httpFields.getValuesList(name);
    }

    @Override
    public String getHost()
    {
        return _httpURI.getHost();
    }

    @Override
    public String getHttpVersion()
    {
        return _httpVersion;
    }

    @Override
    public String getMethod()
    {
        return _method;
    }

    @Override
    public String getOrigin()
    {
        return _httpFields.get(HttpHeader.ORIGIN);
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        return _queryParams;
    }

    @Override
    public String getProtocolVersion()
    {
        return _protocolVersion;
    }

    @Override
    public String getQueryString()
    {
        return _httpURI.getQuery();
    }

    @Override
    public URI getRequestURI()
    {
        HttpURI.Mutable wsURI = HttpURI.build(_httpURI);
        wsURI.scheme(HttpScheme.isSecure(_httpURI.getScheme()) ? HttpScheme.WSS : HttpScheme.WS);
        return wsURI.toURI();
    }

    @Override
    public List<String> getSubProtocols()
    {
        return _subProtocols;
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
        return _subProtocols.stream().anyMatch(s -> s.equalsIgnoreCase(subProtocol));
    }

    @Override
    public boolean isSecure()
    {
        return _secure;
    }
}
