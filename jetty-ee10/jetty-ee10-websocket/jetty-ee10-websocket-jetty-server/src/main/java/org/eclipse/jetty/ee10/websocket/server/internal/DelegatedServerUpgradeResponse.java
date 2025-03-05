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

package org.eclipse.jetty.ee10.websocket.server.internal;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextResponse;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.eclipse.jetty.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;

public class DelegatedServerUpgradeResponse implements JettyServerUpgradeResponse
{
    private final boolean upgraded;
    private final ServerUpgradeResponse upgradeResponse;
    private final HttpServletResponse httpServletResponse;
    private final Map<String, List<String>> headers;
    private final int status;
    private final HttpFields.Mutable httpFields;

    public DelegatedServerUpgradeResponse(ServerUpgradeResponse response)
    {
        this(response, false);
    }

    public DelegatedServerUpgradeResponse(ServerUpgradeResponse response, boolean upgraded)
    {
        this.upgraded = upgraded;
        this.upgradeResponse = response;
        this.httpServletResponse = (HttpServletResponse)Response.as(response, ServletContextResponse.class).getRequest()
            .getAttribute(WebSocketConstants.WEBSOCKET_WRAPPED_RESPONSE_ATTRIBUTE);

        this.httpFields = upgradeResponse.getHeaders();
        this.headers = HttpFields.asMap(upgraded ? httpFields.asImmutable() : httpFields);

        // Fake status code if already upgraded, as it not set at the time this is created.
        HttpVersion httpVersion = response.getRequest().getConnectionMetaData().getHttpVersion();
        this.status = (httpVersion == HttpVersion.HTTP_1_1) ? HttpStatus.SWITCHING_PROTOCOLS_101 : HttpStatus.OK_200;
    }

    @Override
    public void addHeader(String name, String value)
    {
        if (upgraded)
            throw new IllegalStateException("Already Upgraded to WebSocket");

        httpFields.add(name, value);
    }

    @Override
    public void setHeader(String name, String value)
    {
        if (upgraded)
            throw new IllegalStateException("Already Upgraded to WebSocket");

        httpFields.put(name, List.of(value));
    }

    @Override
    public void setHeader(String name, List<String> values)
    {
        if (upgraded)
            throw new IllegalStateException("Already Upgraded to WebSocket");

        httpFields.put(name, values);
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return upgradeResponse.getAcceptedSubProtocol();
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return upgradeResponse.getExtensions().stream().map(JettyExtensionConfig::new).collect(Collectors.toList());
    }

    @Override
    public String getHeader(String name)
    {
        return httpFields.get(name);
    }

    @Override
    public Set<String> getHeaderNames()
    {
        return httpFields.getFieldNamesCollection();
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return headers;
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return httpFields.getValuesList(name);
    }

    @Override
    public int getStatusCode()
    {
        if (upgraded)
            return status;
        else
            return httpServletResponse.getStatus();
    }

    @Override
    public void sendForbidden(String message) throws IOException
    {
        if (upgraded)
            throw new IllegalStateException("Already Upgraded to WebSocket");

        httpServletResponse.sendError(HttpStatus.FORBIDDEN_403, message);
    }

    @Override
    public void setAcceptedSubProtocol(String protocol)
    {
        if (upgraded)
            throw new IllegalStateException("Already Upgraded to WebSocket");

        upgradeResponse.setAcceptedSubProtocol(protocol);
    }

    @Override
    public void setExtensions(List<ExtensionConfig> configs)
    {
        if (upgraded)
            throw new IllegalStateException("Already Upgraded to WebSocket");

        upgradeResponse.setExtensions(configs.stream()
            .map(c -> new org.eclipse.jetty.websocket.core.ExtensionConfig(c.getName(), c.getParameters()))
            .collect(Collectors.toList()));
    }

    @Override
    public void setStatusCode(int statusCode)
    {
        if (upgraded)
            throw new IllegalStateException("Already Upgraded to WebSocket");

        httpServletResponse.setStatus(statusCode);
    }

    @Override
    public boolean isCommitted()
    {
        if (upgraded)
            return true;

        return httpServletResponse.isCommitted();
    }

    @Override
    public void sendError(int statusCode, String message) throws IOException
    {
        if (upgraded)
            throw new IllegalStateException("Already Upgraded to WebSocket");

        httpServletResponse.sendError(statusCode, message);
    }
}
