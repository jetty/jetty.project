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

import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee9.websocket.jakarta.common.ServerEndpointConfigWrapper;
import org.eclipse.jetty.ee9.websocket.jakarta.server.config.ContainerDefaultConfigurator;

public class BasicServerEndpointConfig extends ServerEndpointConfigWrapper
{
    private final String _path;

    public BasicServerEndpointConfig(Class<?> endpointClass, String path)
    {
        ServerEndpointConfig config = Builder.create(endpointClass, "/")
            .configurator(new ContainerDefaultConfigurator())
            .build();
        _path = path;
        init(config);
    }

    @Override
    public String getPath()
    {
        if (_path == null)
            throw new RuntimeException("Path is undefined");

        return _path;
    }
}
