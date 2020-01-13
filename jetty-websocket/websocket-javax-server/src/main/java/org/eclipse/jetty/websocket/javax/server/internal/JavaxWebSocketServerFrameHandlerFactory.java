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

package org.eclipse.jetty.websocket.javax.server.internal;

import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.javax.client.JavaxWebSocketClientFrameHandlerFactory;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketContainer;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketFrameHandlerMetadata;
import org.eclipse.jetty.websocket.servlet.FrameHandlerFactory;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;

public class JavaxWebSocketServerFrameHandlerFactory extends JavaxWebSocketClientFrameHandlerFactory implements FrameHandlerFactory
{
    public JavaxWebSocketServerFrameHandlerFactory(JavaxWebSocketContainer container)
    {
        super(container, new PathParamIdentifier());
    }

    @Override
    public JavaxWebSocketFrameHandlerMetadata getMetadata(Class<?> endpointClass, EndpointConfig endpointConfig)
    {
        if (javax.websocket.Endpoint.class.isAssignableFrom(endpointClass))
        {
            return createEndpointMetadata((Class<? extends Endpoint>)endpointClass, endpointConfig);
        }

        ServerEndpoint anno = endpointClass.getAnnotation(ServerEndpoint.class);
        if (anno == null)
        {
            return super.getMetadata(endpointClass, endpointConfig);
        }

        UriTemplatePathSpec templatePathSpec = new UriTemplatePathSpec(anno.value());
        JavaxWebSocketFrameHandlerMetadata metadata = new JavaxWebSocketFrameHandlerMetadata(endpointConfig);
        metadata.setUriTemplatePathSpec(templatePathSpec);
        return discoverJavaxFrameHandlerMetadata(endpointClass, metadata);
    }

    @Override
    public FrameHandler newFrameHandler(Object websocketPojo, ServletUpgradeRequest upgradeRequest, ServletUpgradeResponse upgradeResponse)
    {
        return newJavaxWebSocketFrameHandler(websocketPojo, new DelegatedJavaxServletUpgradeRequest(upgradeRequest));
    }
}
