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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;

class UpgradeResponseDelegate implements UpgradeResponse
{
    private final ServerUpgradeResponse response;

    UpgradeResponseDelegate(ServerUpgradeResponse response)
    {
        this.response = response;
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return response.getAcceptedSubProtocol();
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return response.getExtensions().stream()
            .map(JettyExtensionConfig::new)
            .collect(Collectors.toList());
    }

    @Override
    public String getHeader(String name)
    {
        return response.getHeaders().get(name);
    }

    @Override
    public Set<String> getHeaderNames()
    {
        return response.getHeaders().getFieldNamesCollection();
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        Map<String, List<String>> result = new LinkedHashMap<>();
        HttpFields.Mutable headers = response.getHeaders();
        for (HttpField header : headers)
        {
            String name = header.getName();
            result.put(name, headers.getValuesList(name));
        }
        return result;
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return response.getHeaders().getValuesList(name);
    }

    @Override
    public int getStatusCode()
    {
        return response.getStatus();
    }
}
