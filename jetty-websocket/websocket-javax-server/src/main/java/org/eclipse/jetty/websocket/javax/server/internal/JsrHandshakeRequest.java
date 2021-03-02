//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.server.internal;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.websocket.server.HandshakeRequest;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;

public class JsrHandshakeRequest implements HandshakeRequest
{
    private final ServerUpgradeRequest delegate;
    private Map<String, String> pathParams;

    public JsrHandshakeRequest(ServerUpgradeRequest req)
    {
        this.delegate = req;
    }

    public void setPathParams(Map<String, String> pathParams)
    {
        this.pathParams = pathParams;
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
        // The TCK wants the PathParams to be included in the list of Request parameters.
        if (pathParams != null && !pathParams.isEmpty())
        {
            HashMap<String, List<String>> map = new HashMap<>(delegate.getParameterMap());
            for (Map.Entry<String, String> entry : pathParams.entrySet())
            {
                List<String> list = map.get(entry.getKey());
                ArrayList<String> updatedList = new ArrayList<>();
                if (list != null)
                    updatedList.addAll(list);
                updatedList.add(entry.getValue());
                map.put(entry.getKey(), Collections.unmodifiableList(updatedList));
            }
            return map;
        }

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
