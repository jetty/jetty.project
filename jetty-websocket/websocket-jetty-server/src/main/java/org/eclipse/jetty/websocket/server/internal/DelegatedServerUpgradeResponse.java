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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.eclipse.jetty.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeResponse;

public class DelegatedServerUpgradeResponse implements JettyServerUpgradeResponse
{
    private final ServerUpgradeResponse upgradeResponse;

    public DelegatedServerUpgradeResponse(ServerUpgradeResponse response)
    {
        upgradeResponse = response;
    }

    @Override
    public void addHeader(String name, String value)
    {
        upgradeResponse.addHeader(name, value);
    }

    @Override
    public void setHeader(String name, String value)
    {
        upgradeResponse.setHeader(name, value);
    }

    @Override
    public void setHeader(String name, List<String> values)
    {
        upgradeResponse.setHeader(name, values);
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
        return upgradeResponse.getHeader(name);
    }

    @Override
    public Set<String> getHeaderNames()
    {
        return upgradeResponse.getHeaderNames();
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return upgradeResponse.getHeadersMap();
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return upgradeResponse.getHeaders(name);
    }

    @Override
    public int getStatusCode()
    {
        return upgradeResponse.getStatusCode();
    }

    @Override
    public void sendForbidden(String message) throws IOException
    {
        upgradeResponse.sendForbidden(message);
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
        upgradeResponse.setStatusCode(statusCode);
    }

    @Override
    public boolean isCommitted()
    {
        return upgradeResponse.isCommitted();
    }

    @Override
    public void sendError(int statusCode, String message) throws IOException
    {
        upgradeResponse.sendError(statusCode, message);
    }
}
