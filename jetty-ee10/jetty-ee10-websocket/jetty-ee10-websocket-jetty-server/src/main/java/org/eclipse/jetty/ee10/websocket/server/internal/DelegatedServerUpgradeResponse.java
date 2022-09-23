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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextResponse;
import org.eclipse.jetty.ee10.websocket.api.ExtensionConfig;
import org.eclipse.jetty.ee10.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;

public class DelegatedServerUpgradeResponse implements JettyServerUpgradeResponse
{
    private final ServerUpgradeResponse upgradeResponse;
    private final HttpServletResponse httpServletResponse;

    public DelegatedServerUpgradeResponse(ServerUpgradeResponse response)
    {
        upgradeResponse = response;
        ServletContextResponse servletContextResponse = Response.as(response, ServletContextResponse.class);
        this.httpServletResponse = (HttpServletResponse)servletContextResponse.getRequest()
            .getAttribute(WebSocketConstants.WEBSOCKET_WRAPPED_RESPONSE_ATTRIBUTE);
    }

    @Override
    public void addHeader(String name, String value)
    {
        // TODO: This should go to the httpServletResponse for headers but then it won't do interception of the websocket headers
        //  which are done through the jetty-core Response wrapping ServerUpgradeResponse done by websocket-core.
        upgradeResponse.getHeaders().add(name, value);
    }

    @Override
    public void setHeader(String name, String value)
    {
        upgradeResponse.getHeaders().put(name, value);
    }

    @Override
    public void setHeader(String name, List<String> values)
    {
        upgradeResponse.getHeaders().put(name, values);
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
        return upgradeResponse.getHeaders().get(name);
    }

    @Override
    public Set<String> getHeaderNames()
    {
        return upgradeResponse.getHeaders().getFieldNamesCollection();
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        Map<String, List<String>> headers = getHeaderNames().stream()
            .collect(Collectors.toMap((name) -> name, (name) -> new ArrayList<>(getHeaders(name))));
        return Collections.unmodifiableMap(headers);
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return upgradeResponse.getHeaders().getValuesList(name);
    }

    @Override
    public int getStatusCode()
    {
        return httpServletResponse.getStatus();
    }

    @Override
    public void sendForbidden(String message) throws IOException
    {
        httpServletResponse.sendError(HttpStatus.FORBIDDEN_403, message);
    }

    @Override
    public void setAcceptedSubProtocol(String protocol)
    {
        upgradeResponse.setAcceptedSubProtocol(protocol);
    }

    @Override
    public void setExtensions(List<ExtensionConfig> configs)
    {
        upgradeResponse.setExtensions(configs.stream()
            .map(c -> new org.eclipse.jetty.websocket.core.ExtensionConfig(c.getName(), c.getParameters()))
            .collect(Collectors.toList()));
    }

    @Override
    public void setStatusCode(int statusCode)
    {
        httpServletResponse.setStatus(statusCode);
    }

    @Override
    public boolean isCommitted()
    {
        return httpServletResponse.isCommitted();
    }

    @Override
    public void sendError(int statusCode, String message) throws IOException
    {
        httpServletResponse.sendError(statusCode, message);
    }
}
