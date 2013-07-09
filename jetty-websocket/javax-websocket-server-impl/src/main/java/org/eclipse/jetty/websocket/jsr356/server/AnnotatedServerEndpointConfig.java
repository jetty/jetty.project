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
        this.decoders = Collections.unmodifiableList(Arrays.asList(anno.decoders()));
        this.encoders = Collections.unmodifiableList(Arrays.asList(anno.encoders()));
        this.subprotocols = Collections.unmodifiableList(Arrays.asList(anno.subprotocols()));

        // supplied by init lifecycle
        this.extensions = new ArrayList<>();
        this.path = anno.value();
        this.endpointClass = endpointClass;
        // no userProperties in annotation
        this.userProperties = new HashMap<>();
        
        if (anno.configurator() == null)
        {
            this.configurator = BasicServerEndpointConfigurator.INSTANCE;
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
    public List<Class<? extends Encoder>> getEncoders()
    {
        return encoders;
    }

    @Override
    public List<Class<? extends Decoder>> getDecoders()
    {
        return decoders;
    }

    @Override
    public Map<String, Object> getUserProperties()
    {
        return userProperties;
    }

    @Override
    public Class<?> getEndpointClass()
    {
        return endpointClass;
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
    public List<Extension> getExtensions()
    {
        return extensions;
    }

    @Override
    public Configurator getConfigurator()
    {
        return configurator;
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
