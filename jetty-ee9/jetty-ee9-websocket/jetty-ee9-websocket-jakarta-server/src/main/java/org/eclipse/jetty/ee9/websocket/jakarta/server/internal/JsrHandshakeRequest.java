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

package org.eclipse.jetty.ee9.websocket.jakarta.server.internal;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;

import jakarta.websocket.server.HandshakeRequest;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;

public class JsrHandshakeRequest implements HandshakeRequest
{
    private final ServerUpgradeRequest delegate;

    public JsrHandshakeRequest(ServerUpgradeRequest req)
    {
        this.delegate = req;
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return delegate.getHeadersMap();
    }

    @Override
    public Object getHttpSession()
    {
        return delegate.getSession();
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        return delegate.getParameterMap();
    }

    @Override
    public String getQueryString()
    {
        return delegate.getQueryString();
    }

    public PathSpec getRequestPathSpec()
    {
        return (PathSpec)delegate.getServletAttribute(PathSpec.class.getName());
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getPathParams()
    {
        return (Map<String, String>)delegate.getServletAttribute(JakartaWebSocketServerContainer.PATH_PARAM_ATTRIBUTE);
    }

    @Override
    public URI getRequestURI()
    {
        return delegate.getRequestURI();
    }

    @Override
    public Principal getUserPrincipal()
    {
        return delegate.getUserPrincipal();
    }

    @Override
    public boolean isUserInRole(String role)
    {
        return delegate.isUserInRole(role);
    }
}
