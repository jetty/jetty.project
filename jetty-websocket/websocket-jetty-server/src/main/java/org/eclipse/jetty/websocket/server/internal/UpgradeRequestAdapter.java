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

import java.net.HttpCookie;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.websocket.util.server.internal.ServletUpgradeRequest;

public class UpgradeRequestAdapter implements UpgradeRequest
{
    private final ServletUpgradeRequest _servletRequest;

    public UpgradeRequestAdapter(ServletUpgradeRequest servletRequest)
    {
        _servletRequest = servletRequest;
    }

    @Override
    public List<HttpCookie> getCookies()
    {
        return _servletRequest.getCookies();
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return _servletRequest.getExtensions().stream()
            .map((ext) -> new JettyExtensionConfig(ext.getName(), ext.getParameters()))
            .collect(Collectors.toList());
    }

    @Override
    public String getHeader(String name)
    {
        return _servletRequest.getHeader(name);
    }

    @Override
    public int getHeaderInt(String name)
    {
        return _servletRequest.getHeaderInt(name);
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return _servletRequest.getHeadersMap();
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return _servletRequest.getHeaders(name);
    }

    @Override
    public String getHost()
    {
        return _servletRequest.getHost();
    }

    @Override
    public String getHttpVersion()
    {
        return _servletRequest.getHttpVersion();
    }

    @Override
    public String getMethod()
    {
        return _servletRequest.getMethod();
    }

    @Override
    public String getOrigin()
    {
        return _servletRequest.getOrigin();
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        return _servletRequest.getParameterMap();
    }

    @Override
    public String getProtocolVersion()
    {
        return _servletRequest.getProtocolVersion();
    }

    @Override
    public String getQueryString()
    {
        return _servletRequest.getQueryString();
    }

    @Override
    public URI getRequestURI()
    {
        return _servletRequest.getRequestURI();
    }

    @Override
    public Object getSession()
    {
        return _servletRequest.getSession();
    }

    @Override
    public List<String> getSubProtocols()
    {
        return _servletRequest.getSubProtocols();
    }

    @Override
    public Principal getUserPrincipal()
    {
        return _servletRequest.getUserPrincipal();
    }

    @Override
    public boolean hasSubProtocol(String test)
    {
        return _servletRequest.hasSubProtocol(test);
    }

    @Override
    public boolean isSecure()
    {
        return _servletRequest.isSecure();
    }
}
