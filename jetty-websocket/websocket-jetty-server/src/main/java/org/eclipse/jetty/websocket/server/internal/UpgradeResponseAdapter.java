//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
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

import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;

public class UpgradeResponseAdapter implements UpgradeResponse
{
    private final ServletUpgradeResponse servletResponse;

    public UpgradeResponseAdapter(ServletUpgradeResponse servletResponse)
    {
        this.servletResponse = servletResponse;
    }

    @Override
    public void addHeader(String name, String value)
    {
        this.servletResponse.addHeader(name, value);
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return this.servletResponse.getAcceptedSubProtocol();
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return this.servletResponse.getExtensions().stream()
            .map((ext) -> new JettyExtensionConfig(ext.getName(), ext.getParameters()))
            .collect(Collectors.toList());
    }

    @Override
    public String getHeader(String name)
    {
        return this.servletResponse.getHeader(name);
    }

    @Override
    public Set<String> getHeaderNames()
    {
        return this.servletResponse.getHeaderNames();
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return this.servletResponse.getHeadersMap();
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return this.servletResponse.getHeaders(name);
    }

    @Override
    public int getStatusCode()
    {
        return this.servletResponse.getStatusCode();
    }

    @Override
    public void sendForbidden(String message) throws IOException
    {
        this.servletResponse.sendForbidden(message);
    }

    @Override
    public void setAcceptedSubProtocol(String protocol)
    {
        this.servletResponse.setAcceptedSubProtocol(protocol);
    }

    @Override
    public void setExtensions(List<ExtensionConfig> extensions)
    {
        List<org.eclipse.jetty.websocket.core.ExtensionConfig> coreExtensionConfigs = extensions.stream()
            .map((ext) -> new org.eclipse.jetty.websocket.core.ExtensionConfig(ext.getName(), ext.getParameters()))
            .collect(Collectors.toList());
        this.servletResponse.setExtensions(coreExtensionConfigs);
    }

    @Override
    public void setHeader(String name, String value)
    {
        this.servletResponse.setHeader(name, value);
    }

    @Override
    public void setStatusCode(int statusCode)
    {
        this.servletResponse.setStatusCode(statusCode);
    }
}
