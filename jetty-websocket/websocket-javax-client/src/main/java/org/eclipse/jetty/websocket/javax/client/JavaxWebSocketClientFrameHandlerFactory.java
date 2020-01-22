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

package org.eclipse.jetty.websocket.javax.client;

import javax.websocket.ClientEndpoint;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketContainer;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketFrameHandlerMetadata;
import org.eclipse.jetty.websocket.javax.common.util.InvokerUtils;

public class JavaxWebSocketClientFrameHandlerFactory extends JavaxWebSocketFrameHandlerFactory
{
    public JavaxWebSocketClientFrameHandlerFactory(JavaxWebSocketContainer container, InvokerUtils.ParamIdentifier paramIdentifier)
    {
        super(container, paramIdentifier);
    }

    public JavaxWebSocketClientFrameHandlerFactory(JavaxWebSocketContainer container)
    {
        super(container, InvokerUtils.PARAM_IDENTITY);
    }

    @Override
    public EndpointConfig newDefaultEndpointConfig(Class<?> endpointClass, String path)
    {
        return new BasicClientEndpointConfig();
    }

    @Override
    public JavaxWebSocketFrameHandlerMetadata getMetadata(Class<?> endpointClass, EndpointConfig endpointConfig)
    {
        if (javax.websocket.Endpoint.class.isAssignableFrom(endpointClass))
        {
            return createEndpointMetadata((Class<? extends Endpoint>)endpointClass, endpointConfig);
        }

        if (endpointClass.getAnnotation(ClientEndpoint.class) == null)
        {
            return null;
        }

        JavaxWebSocketFrameHandlerMetadata metadata = new JavaxWebSocketFrameHandlerMetadata(endpointConfig);
        return discoverJavaxFrameHandlerMetadata(endpointClass, metadata);
    }
}
