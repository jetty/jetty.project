//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

public class AnnotatedServerEndpointConfig implements ServerEndpointConfig
{
    private final Class<?> endpointClass;
    private final String path;
    private final List<Class<? extends Decoder>> decoders;
    private final List<Class<? extends Encoder>> encoders;
    private final Configurator configurator;
    private final List<String> subprotocols;

    private Map<String, Object> userProperties;
    private List<Extension> extensions;

    public AnnotatedServerEndpointConfig(Class<?> endpointClass, ServerEndpoint anno) throws DeploymentException
    {
        this(endpointClass,anno,null);
    }

    public AnnotatedServerEndpointConfig(Class<?> endpointClass, ServerEndpoint anno, ServerEndpointConfig baseConfig) throws DeploymentException
    {
        List<Class<? extends Decoder>> compositeDecoders = new ArrayList<>();
        List<Class<? extends Encoder>> compositeEncoders = new ArrayList<>();
        List<String> compositeSubProtocols = new ArrayList<>();

        Configurator configr = null;

        // Copy from base config
        if (baseConfig != null)
        {
            compositeDecoders.addAll(baseConfig.getDecoders());
            compositeEncoders.addAll(baseConfig.getEncoders());
            compositeSubProtocols.addAll(baseConfig.getSubprotocols());
            configr = baseConfig.getConfigurator();
        }

        // now add from annotations
        compositeDecoders.addAll(Arrays.asList(anno.decoders()));
        compositeEncoders.addAll(Arrays.asList(anno.encoders()));
        compositeSubProtocols.addAll(Arrays.asList(anno.subprotocols()));

        // Create unmodifiable lists
        this.decoders = Collections.unmodifiableList(compositeDecoders);
        this.encoders = Collections.unmodifiableList(compositeEncoders);
        this.subprotocols = Collections.unmodifiableList(compositeSubProtocols);

        // supplied by init lifecycle
        this.extensions = new ArrayList<>();
        this.path = anno.value();
        this.endpointClass = endpointClass;
        // no userProperties in annotation
        this.userProperties = new HashMap<>();

        if (anno.configurator() == null)
        {
            if (configr != null)
            {
                this.configurator = configr;
            }
            else
            {
                this.configurator = BasicServerEndpointConfigurator.INSTANCE;
            }
        }
        else
        {
            try
            {
                this.configurator = anno.configurator().newInstance();
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
    }

    @Override
    public Configurator getConfigurator()
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
