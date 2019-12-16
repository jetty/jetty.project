//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.server.internal;

import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.websocket.javax.common.ServerEndpointConfigWrapper;
import org.eclipse.jetty.websocket.javax.server.config.ContainerDefaultConfigurator;

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
