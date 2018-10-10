//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server.internal;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
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
                .map((ext)->new org.eclipse.jetty.websocket.api.extensions.ExtensionConfig(ext.getName(), ext.getParameters()))
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
                .map((ext)->new org.eclipse.jetty.websocket.core.ExtensionConfig(ext.getName(), ext.getParameters()))
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
