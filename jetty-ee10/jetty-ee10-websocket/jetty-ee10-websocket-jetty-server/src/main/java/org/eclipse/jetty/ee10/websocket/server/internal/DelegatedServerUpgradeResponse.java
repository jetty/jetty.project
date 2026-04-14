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
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.eclipse.jetty.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;

/**
 * Implements the {@link JettyServerUpgradeResponse} interface by delegating to the core {@link ServerUpgradeResponse}.
 * <p>
 * This class is to be used during the WebSocket negotiation phase on the server side.
 */
public class DelegatedServerUpgradeResponse implements JettyServerUpgradeResponse
{
    private final ServerUpgradeResponse _upgradeResponse;
    private final HttpServletResponse _httpServletResponse;
    private final HttpFields.Mutable _httpFields;

    public DelegatedServerUpgradeResponse(ServerUpgradeResponse response)
    {
        _upgradeResponse = response;
        // Use the HttpFields from the UpgradeResponse because it specially handles websocket headers.
        _httpFields = _upgradeResponse.getHeaders();
        _httpServletResponse = (HttpServletResponse)response.getRequest()
            .getAttribute(WebSocketConstants.WEBSOCKET_WRAPPED_RESPONSE_ATTRIBUTE);
    }

    @Override
    public void addHeader(String name, String value)
    {
        _httpFields.add(name, value);
    }

    @Override
    public void setHeader(String name, String value)
    {
        _httpFields.put(name, List.of(value));
    }

    @Override
    public void setHeader(String name, List<String> values)
    {
        _httpFields.put(name, values);
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return _upgradeResponse.getAcceptedSubProtocol();
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return _upgradeResponse.getExtensions().stream().map(JettyExtensionConfig::new).collect(Collectors.toList());
    }

    @Override
    public String getHeader(String name)
    {
        return _httpFields.get(name);
    }

    @Override
    public Set<String> getHeaderNames()
    {
        return _httpFields.getFieldNamesCollection();
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
    public int getStatusCode()
    {
        return _upgradeResponse.getStatus();
    }

    @Override
    public void sendForbidden(String message) throws IOException
    {
        _httpServletResponse.sendError(HttpStatus.FORBIDDEN_403, message);
    }

    @Override
    public void setAcceptedSubProtocol(String protocol)
    {
        _upgradeResponse.setAcceptedSubProtocol(protocol);
    }

    @Override
    public void setExtensions(List<ExtensionConfig> configs)
    {
        _upgradeResponse.setExtensions(configs.stream()
            .map(c -> new org.eclipse.jetty.websocket.core.ExtensionConfig(c.getName(), c.getParameters()))
            .collect(Collectors.toList()));
    }

    @Override
    public void setStatusCode(int statusCode)
    {
        _upgradeResponse.setStatus(statusCode);
    }

    @Override
    public boolean isCommitted()
    {
        return _upgradeResponse.isCommitted();
    }

    @Override
    public void sendError(int statusCode, String message) throws IOException
    {
        _httpServletResponse.sendError(statusCode, message);
    }
}
