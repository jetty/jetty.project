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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;

/**
 * Internal implementation of the {@link UpgradeResponse} interface.
 * This takes a {@link ServerUpgradeResponse} instance and copies all required information after the WebSocket upgrade,
 * to store for the duration of the WebSocket connection, past the end of the HTTP request lifecycle.
 */
public class CompletedUpgradeResponse implements UpgradeResponse
{
    private final HttpFields _httpFields;
    private final Map<String, List<String>> _headers;
    private final List<ExtensionConfig> _negotiatedExtensions;
    private final int _status;
    private final String _acceptedSubProtocol;

    public CompletedUpgradeResponse(ServerUpgradeResponse response)
    {
        _httpFields = response.getHeaders().asImmutable();
        _headers = HttpFields.asMap(response.getHeaders());
        _status = response.getStatus();
        _acceptedSubProtocol = response.getAcceptedSubProtocol();

        List<ExtensionConfig> extensionConfigs = new ArrayList<>();
        for (org.eclipse.jetty.websocket.core.ExtensionConfig extensionConfig : response.getExtensions())
        {
            extensionConfigs.add(new JettyExtensionConfig(extensionConfig));
        }
        _negotiatedExtensions = Collections.unmodifiableList(extensionConfigs);
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return _acceptedSubProtocol;
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return _negotiatedExtensions;
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
        return _headers;
    }

    @Override
    public List<String> getHeaders(String name)
    {
        return _httpFields.getValuesList(name);
    }

    @Override
    public int getStatusCode()
    {
        return _status;
    }
}
