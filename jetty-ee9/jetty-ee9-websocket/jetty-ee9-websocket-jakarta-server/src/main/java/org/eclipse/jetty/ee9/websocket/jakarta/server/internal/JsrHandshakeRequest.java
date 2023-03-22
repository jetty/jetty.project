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

package org.eclipse.jetty.ee9.websocket.jakarta.server.internal;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.websocket.server.HandshakeRequest;
import org.eclipse.jetty.ee9.websocket.jakarta.server.JakartaWebSocketServerContainer;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;

public class JsrHandshakeRequest implements HandshakeRequest
{
    private final ServerUpgradeRequest delegate;
    private final HttpServletRequest httpServletRequest;
    private Map<String, List<String>> parameterMap;

    public JsrHandshakeRequest(ServerUpgradeRequest req)
    {
        this.delegate = req;
        this.httpServletRequest = (HttpServletRequest)req
            .getAttribute(WebSocketConstants.WEBSOCKET_WRAPPED_REQUEST_ATTRIBUTE);
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        Map<String, List<String>> headers = delegate.getHeaders().getFieldNamesCollection().stream()
            .collect(Collectors.toMap((name) -> name, (name) -> new ArrayList<>(delegate.getHeaders().getValuesList(name))));
        return Collections.unmodifiableMap(headers);
    }

    @Override
    public Object getHttpSession()
    {
        return httpServletRequest.getSession(false);
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        if (parameterMap == null)
        {
            Fields requestParams = Request.extractQueryParameters(delegate);
            parameterMap = new HashMap<>();
            for (String name : requestParams.getNames())
            {
                parameterMap.put(name, requestParams.getValues(name));
            }
        }
        return parameterMap;
    }

    @Override
    public String getQueryString()
    {
        return delegate.getHttpURI().getQuery();
    }

    public PathSpec getRequestPathSpec()
    {
        return (PathSpec)delegate.getAttribute(PathSpec.class.getName());
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getPathParams()
    {
        return (Map<String, String>)delegate.getAttribute(JakartaWebSocketServerContainer.PATH_PARAM_ATTRIBUTE);
    }

    @Override
    public URI getRequestURI()
    {
        return delegate.getHttpURI().toURI();
    }

    @Override
    public Principal getUserPrincipal()
    {
        return httpServletRequest.getUserPrincipal();
    }

    @Override
    public boolean isUserInRole(String role)
    {
        return httpServletRequest.isUserInRole(role);
    }
}
