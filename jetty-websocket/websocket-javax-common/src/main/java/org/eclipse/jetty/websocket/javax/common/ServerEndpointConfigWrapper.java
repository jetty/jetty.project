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

package org.eclipse.jetty.websocket.javax.common;

import java.util.List;
import javax.websocket.Extension;
import javax.websocket.server.ServerEndpointConfig;

public class ServerEndpointConfigWrapper extends EndpointConfigWrapper implements ServerEndpointConfig
{
    private ServerEndpointConfig _endpointConfig;

    public ServerEndpointConfigWrapper()
    {
    }

    public ServerEndpointConfigWrapper(ServerEndpointConfig endpointConfig)
    {
        init(endpointConfig);
    }

    public void init(ServerEndpointConfig endpointConfig)
    {
        _endpointConfig = endpointConfig;
        super.init(endpointConfig);
    }

    @Override
    public Class<?> getEndpointClass()
    {
        return _endpointConfig.getEndpointClass();
    }

    @Override
    public String getPath()
    {
        return _endpointConfig.getPath();
    }

    @Override
    public List<String> getSubprotocols()
    {
        return _endpointConfig.getSubprotocols();
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
