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

package org.eclipse.jetty.ee9.websocket.jakarta.common;

import java.util.List;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.Extension;

public class ClientEndpointConfigWrapper extends EndpointConfigWrapper implements ClientEndpointConfig
{
    private ClientEndpointConfig _endpointConfig;

    public ClientEndpointConfigWrapper()
    {
    }

    public ClientEndpointConfigWrapper(ClientEndpointConfig endpointConfig)
    {
        init(endpointConfig);
    }

    public void init(ClientEndpointConfig endpointConfig)
    {
        _endpointConfig = endpointConfig;
        super.init(endpointConfig);
    }

    @Override
    public List<String> getPreferredSubprotocols()
    {
        return _endpointConfig.getPreferredSubprotocols();
    }

    @Override
    public List<Extension> getExtensions()
    {
        return _endpointConfig.getExtensions();
    }

    @Override
    public Configurator getConfigurator()
    {
        return _endpointConfig.getConfigurator();
    }
}
