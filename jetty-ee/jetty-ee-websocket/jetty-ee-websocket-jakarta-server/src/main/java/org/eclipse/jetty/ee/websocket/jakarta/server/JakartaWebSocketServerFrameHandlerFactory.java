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

package org.eclipse.jetty.ee.websocket.jakarta.server;

import java.util.Map;

import jakarta.websocket.DeploymentException;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee.websocket.jakarta.client.JakartaWebSocketClientFrameHandlerFactory;
import org.eclipse.jetty.ee.websocket.jakarta.common.JakartaWebSocketContainer;
import org.eclipse.jetty.ee.websocket.jakarta.common.JakartaWebSocketFrameHandler;
import org.eclipse.jetty.ee.websocket.jakarta.common.JakartaWebSocketFrameHandlerMetadata;
import org.eclipse.jetty.ee.websocket.jakarta.common.JakartaWebSocketMessageMetadata;
import org.eclipse.jetty.ee.websocket.jakarta.common.PutListenerMap;
import org.eclipse.jetty.ee.websocket.jakarta.common.UpgradeRequest;
import org.eclipse.jetty.ee.websocket.jakarta.server.internal.JakartaServerUpgradeRequest;
import org.eclipse.jetty.ee.websocket.jakarta.server.internal.PathParamIdentifier;
import org.eclipse.jetty.ee.websocket.jakarta.server.internal.ServerEndpointConfigWrapper;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.exception.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.server.FrameHandlerFactory;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.core.util.MethodHolder;

public class JakartaWebSocketServerFrameHandlerFactory extends JakartaWebSocketClientFrameHandlerFactory implements FrameHandlerFactory
{
    public JakartaWebSocketServerFrameHandlerFactory(JakartaWebSocketContainer container)
    {
        super(container, new PathParamIdentifier());
    }

    @Override
    public JakartaWebSocketFrameHandlerMetadata getMetadata(Class<?> endpointClass, EndpointConfig endpointConfig) throws DeploymentException
    {
        if (jakarta.websocket.Endpoint.class.isAssignableFrom(endpointClass))
            return createEndpointMetadata(endpointConfig);

        ServerEndpoint anno = endpointClass.getAnnotation(ServerEndpoint.class);
        if (anno == null)
            return super.getMetadata(endpointClass, endpointConfig);

        UriTemplatePathSpec templatePathSpec = new UriTemplatePathSpec(anno.value());
        JakartaWebSocketFrameHandlerMetadata metadata = new JakartaWebSocketFrameHandlerMetadata(endpointConfig, components);
        metadata.setUriTemplatePathSpec(templatePathSpec);
        return discoverJakartaFrameHandlerMetadata(endpointClass, metadata);
    }

    @Override
    public FrameHandler newFrameHandler(Object websocketPojo, ServerUpgradeRequest upgradeRequest, ServerUpgradeResponse upgradeResponse)
    {
        try
        {
            return createJakartaWebSocketFrameHandler(websocketPojo, new JakartaServerUpgradeRequest(upgradeRequest));
        }
        catch (DeploymentException e)
        {
            throw new InvalidWebSocketException(e.getMessage(), e);
        }
    }

    @Override
    protected JakartaWebSocketFrameHandler newJakartaWebSocketFrameHandler(JakartaWebSocketContainer container,
                                                                           UpgradeRequest upgradeRequest,
                                                                           Object endpointInstance,
                                                                           MethodHolder openHandle, MethodHolder closeHandle, MethodHolder errorHandle,
                                                                           JakartaWebSocketMessageMetadata textMetadata, JakartaWebSocketMessageMetadata binaryMetadata,
                                                                           MethodHolder pongHandle,
                                                                           EndpointConfig endpointConfig)
    {
        return new JakartaWebSocketFrameHandler(container, upgradeRequest, endpointInstance, openHandle, closeHandle, errorHandle, textMetadata, binaryMetadata, pongHandle, endpointConfig)
        {
            @Override
            protected EndpointConfig getWrappedEndpointConfig()
            {
                EndpointConfig endpointConfig = getEndpointConfig();
                if (endpointConfig instanceof ServerEndpointConfig)
                {
                    final Map<String, Object> listenerMap = new PutListenerMap(endpointConfig.getUserProperties(), this::configListener);
                    return new ServerEndpointConfigWrapper((ServerEndpointConfig)endpointConfig)
                    {
                        @Override
                        public Map<String, Object> getUserProperties()
                        {
                            return listenerMap;
                        }
                    };
                }

                return super.getWrappedEndpointConfig();
            }
        };
    }
}
