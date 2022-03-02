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

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.EndpointConfig;
import org.eclipse.jetty.websocket.core.internal.util.InvokerUtils;

public class DummyFrameHandlerFactory extends JakartaWebSocketFrameHandlerFactory
{
    public DummyFrameHandlerFactory(JakartaWebSocketContainer container)
    {
        super(container, InvokerUtils.PARAM_IDENTITY);
    }

    @Override
    public EndpointConfig newDefaultEndpointConfig(Class<?> endpointClass)
    {
        return ClientEndpointConfig.Builder.create().build();
    }

    @Override
    public JakartaWebSocketFrameHandlerMetadata getMetadata(Class<?> endpointClass, EndpointConfig endpointConfig)
    {
        if (jakarta.websocket.Endpoint.class.isAssignableFrom(endpointClass))
        {
            return createEndpointMetadata(endpointConfig);
        }

        if (endpointClass.getAnnotation(ClientEndpoint.class) == null)
        {
            return null;
        }

        JakartaWebSocketFrameHandlerMetadata metadata = new JakartaWebSocketFrameHandlerMetadata(endpointConfig, components);
        return discoverJakartaFrameHandlerMetadata(endpointClass, metadata);
    }
}
