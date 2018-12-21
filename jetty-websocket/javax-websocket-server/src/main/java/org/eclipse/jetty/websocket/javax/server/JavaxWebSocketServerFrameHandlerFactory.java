//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.server;

import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.javax.server.internal.PathParamIdentifier;
import org.eclipse.jetty.websocket.javax.server.internal.UpgradeRequestAdapter;
import org.eclipse.jetty.websocket.javax.server.internal.UpgradeResponseAdapter;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketContainer;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketFrameHandlerMetadata;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.FrameHandlerFactory;

import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import java.util.concurrent.CompletableFuture;

public class JavaxWebSocketServerFrameHandlerFactory extends JavaxWebSocketFrameHandlerFactory implements FrameHandlerFactory
{
    public JavaxWebSocketServerFrameHandlerFactory(JavaxWebSocketContainer container)
    {
        super(container, new PathParamIdentifier());
    }

    @Override
    public JavaxWebSocketFrameHandlerMetadata createMetadata(Class<?> endpointClass, EndpointConfig endpointConfig)
    {
        if (javax.websocket.Endpoint.class.isAssignableFrom(endpointClass))
        {
            return createEndpointMetadata((Class<? extends Endpoint>)endpointClass, endpointConfig);
        }

        ServerEndpoint anno = endpointClass.getAnnotation(ServerEndpoint.class);

        if (anno == null)
        {
            return null;
        }

        UriTemplatePathSpec templatePathSpec = new UriTemplatePathSpec(anno.value());
        JavaxWebSocketFrameHandlerMetadata metadata = new JavaxWebSocketFrameHandlerMetadata(endpointConfig);
        metadata.setUriTemplatePathSpec(templatePathSpec);
        return discoverJavaxFrameHandlerMetadata(endpointClass, metadata);
    }

    @Override
    public FrameHandler newFrameHandler(Object websocketPojo, ServletUpgradeRequest upgradeRequest, ServletUpgradeResponse upgradeResponse)
    {
        CompletableFuture<Session> completableFuture = new CompletableFuture<>();
        return newJavaxFrameHandler(websocketPojo, new UpgradeRequestAdapter(upgradeRequest), new UpgradeResponseAdapter(upgradeResponse), completableFuture);
    }
}
