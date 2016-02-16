//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.websocket.Decoder;
import javax.websocket.DeploymentException;
import javax.websocket.Encoder;
import javax.websocket.Extension;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

public class AnnotatedServerEndpointConfig implements ServerEndpointConfig
{
    private final Class<?> endpointClass;
    private final String path;
    private final List<Class<? extends Decoder>> decoders;
    private final List<Class<? extends Encoder>> encoders;
    private final ServerEndpointConfig.Configurator configurator;
    private final List<String> subprotocols;

    private Map<String, Object> userProperties;
    private List<Extension> extensions;

    public AnnotatedServerEndpointConfig(WebSocketContainerScope containerScope, Class<?> endpointClass, ServerEndpoint anno) throws DeploymentException
    {
        this(containerScope,endpointClass,anno,null);
    }

    public AnnotatedServerEndpointConfig(WebSocketContainerScope containerScope, Class<?> endpointClass, ServerEndpoint anno, ServerEndpointConfig baseConfig) throws DeploymentException
    {
        ServerEndpointConfig.Configurator configr = null;

        // Copy from base config
        if (baseConfig != null)
        {
            configr = baseConfig.getConfigurator();
        }

        // Decoders (favor provided config over annotation)
        if (baseConfig != null && baseConfig.getDecoders() != null && baseConfig.getDecoders().size() > 0)
        {
            this.decoders = Collections.unmodifiableList(baseConfig.getDecoders());
        }
        else
        {
            this.decoders = Collections.unmodifiableList(Arrays.asList(anno.decoders()));
        }

        // Encoders (favor provided config over annotation)
        if (baseConfig != null && baseConfig.getEncoders() != null && baseConfig.getEncoders().size() > 0)
        {
            this.encoders = Collections.unmodifiableList(baseConfig.getEncoders());
        }
        else
        {
            this.encoders = Collections.unmodifiableList(Arrays.asList(anno.encoders()));
        }

        // Sub Protocols (favor provided config over annotation)
        if (baseConfig != null && baseConfig.getSubprotocols() != null && baseConfig.getSubprotocols().size() > 0)
        {
            this.subprotocols = Collections.unmodifiableList(baseConfig.getSubprotocols());
        }
        else
        {
            this.subprotocols = Collections.unmodifiableList(Arrays.asList(anno.subprotocols()));
        }

        // Path (favor provided config over annotation)
        if (baseConfig != null && baseConfig.getPath() != null && baseConfig.getPath().length() > 0)
        {
            this.path = baseConfig.getPath();
        }
        else
        {
            this.path = anno.value();
        }

        // supplied by init lifecycle
        this.extensions = new ArrayList<>();
        // always what is passed in
        this.endpointClass = endpointClass;
        // UserProperties in annotation
        this.userProperties = new HashMap<>();
        if (baseConfig != null && baseConfig.getUserProperties() != null && baseConfig.getUserProperties().size() > 0)
        {
            userProperties.putAll(baseConfig.getUserProperties());
        }
        
        ServerEndpointConfig.Configurator cfgr;

        if (anno.configurator() == ServerEndpointConfig.Configurator.class)
        {
            if (configr != null)
            {
                cfgr = configr;
            }
            else
            {
                cfgr = new ContainerDefaultConfigurator();
            }
        }
        else
        {
            try
            {
                cfgr = anno.configurator().newInstance();
            }
            catch (InstantiationException | IllegalAccessException e)
            {
                StringBuilder err = new StringBuilder();
                err.append("Unable to instantiate ClientEndpoint.configurator() of ");
                err.append(anno.configurator().getName());
                err.append(" defined as annotation in ");
                err.append(anno.getClass().getName());
                throw new DeploymentException(err.toString(),e);
            }
        }
        
        // Make sure all Configurators obtained are decorated
        this.configurator = containerScope.getObjectFactory().decorate(cfgr);
    }

    @Override
    public ServerEndpointConfig.Configurator getConfigurator()
    {
        return configurator;
    }

    @Override
    public List<Class<? extends Decoder>> getDecoders()
    {
        return decoders;
    }

    @Override
    public List<Class<? extends Encoder>> getEncoders()
    {
        return encoders;
    }

    @Override
    public Class<?> getEndpointClass()
    {
        return endpointClass;
    }

    @Override
    public List<Extension> getExtensions()
    {
        return extensions;
    }

    @Override
    public String getPath()
    {
        return path;
    }

    @Override
    public List<String> getSubprotocols()
    {
        return subprotocols;
    }

    @Override
    public Map<String, Object> getUserProperties()
    {
        return userProperties;
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("AnnotatedServerEndpointConfig[endpointClass=");
        builder.append(endpointClass);
        builder.append(",path=");
        builder.append(path);
        builder.append(",decoders=");
        builder.append(decoders);
        builder.append(",encoders=");
        builder.append(encoders);
        builder.append(",subprotocols=");
        builder.append(subprotocols);
        builder.append(",extensions=");
        builder.append(extensions);
        builder.append("]");
        return builder.toString();
    }
}
