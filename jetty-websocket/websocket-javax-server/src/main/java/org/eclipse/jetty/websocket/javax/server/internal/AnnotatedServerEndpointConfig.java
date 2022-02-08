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

package org.eclipse.jetty.websocket.javax.server.internal;

import java.util.ArrayList;
import java.util.List;
import javax.websocket.Decoder;
import javax.websocket.DeploymentException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketContainer;
import org.eclipse.jetty.websocket.javax.common.ServerEndpointConfigWrapper;
import org.eclipse.jetty.websocket.javax.server.config.ContainerDefaultConfigurator;

public class AnnotatedServerEndpointConfig extends ServerEndpointConfigWrapper
{
    public AnnotatedServerEndpointConfig(JavaxWebSocketContainer containerScope, Class<?> endpointClass, ServerEndpoint anno) throws DeploymentException
    {
        this(containerScope, endpointClass, anno, null);
    }

    public AnnotatedServerEndpointConfig(JavaxWebSocketContainer containerScope, Class<?> endpointClass, ServerEndpoint anno, EndpointConfig baseConfig) throws DeploymentException
    {
        // Provided Base EndpointConfig.
        ServerEndpointConfig baseServerConfig = null;
        if (baseConfig instanceof ServerEndpointConfig)
        {
            baseServerConfig = (ServerEndpointConfig)baseConfig;
        }

        // Decoders (favor provided config over annotation).
        List<Class<? extends Decoder>> decoders;
        if (baseConfig != null && baseConfig.getDecoders() != null && baseConfig.getDecoders().size() > 0)
            decoders = baseConfig.getDecoders();
        else
            decoders = List.of(anno.decoders());

        // AvailableEncoders (favor provided config over annotation).
        List<Class<? extends Encoder>> encoders;
        if (baseConfig != null && baseConfig.getEncoders() != null && baseConfig.getEncoders().size() > 0)
            encoders = baseConfig.getEncoders();
        else
            encoders = List.of(anno.encoders());

        // Sub Protocols (favor provided config over annotation).
        List<String> subprotocols;
        if (baseServerConfig != null && baseServerConfig.getSubprotocols() != null && baseServerConfig.getSubprotocols().size() > 0)
            subprotocols = baseServerConfig.getSubprotocols();
        else
            subprotocols = List.of(anno.subprotocols());

        // Path (favor provided config over annotation).
        String path;
        if (baseServerConfig != null && baseServerConfig.getPath() != null && baseServerConfig.getPath().length() > 0)
            path = baseServerConfig.getPath();
        else
            path = anno.value();

        ServerEndpointConfig.Configurator configurator = getConfigurator(baseServerConfig, anno, containerScope);

        // Build a ServerEndpointConfig with the Javax API builder to wrap.
        ServerEndpointConfig endpointConfig = ServerEndpointConfig.Builder.create(endpointClass, path)
            .configurator(configurator)
            .encoders(encoders)
            .decoders(decoders)
            .extensions(new ArrayList<>())
            .subprotocols(subprotocols)
            .build();

        // Set the UserProperties from annotation into the new EndpointConfig.
        if (baseConfig != null && baseConfig.getUserProperties() != null && baseConfig.getUserProperties().size() > 0)
            endpointConfig.getUserProperties().putAll(baseConfig.getUserProperties());

        init(endpointConfig);
    }

    private static Configurator getConfigurator(ServerEndpointConfig baseServerConfig, ServerEndpoint anno, JavaxWebSocketContainer containerScope) throws DeploymentException
    {
        Configurator ret = null;

        // Copy from base config
        if (baseServerConfig != null)
        {
            ret = baseServerConfig.getConfigurator();
        }

        if (anno != null)
        {
            // Is this using the JSR356 spec/api default?
            if (anno.configurator() == ServerEndpointConfig.Configurator.class)
            {
                // Return the spec default impl if one wasn't provided as part of the base config
                if (ret == null)
                    return new ContainerDefaultConfigurator();
                else
                    return ret;
            }

            // Instantiate the provided configurator
            try
            {
                return containerScope.getObjectFactory().createInstance(anno.configurator());
            }
            catch (Exception e)
            {
                StringBuilder err = new StringBuilder();
                err.append("Unable to instantiate ServerEndpoint.configurator() of ");
                err.append(anno.configurator().getName());
                err.append(" defined as annotation in ");
                err.append(anno.getClass().getName());
                throw new DeploymentException(err.toString(), e);
            }
        }

        return ret;
    }
}
