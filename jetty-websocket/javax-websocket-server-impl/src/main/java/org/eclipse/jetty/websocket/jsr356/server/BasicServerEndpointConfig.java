//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.Extension;
import javax.websocket.server.ServerEndpointConfig;

public class BasicServerEndpointConfig implements ServerEndpointConfig
{
    private final List<Class<? extends Decoder>> decoders;
    private final List<Class<? extends Encoder>> encoders;
    private final List<Extension> extensions;
    private final List<String> subprotocols;
    private final ServerEndpointConfig.Configurator configurator;
    private final Class<?> endpointClass;
    private final String path;
    private Map<String, Object> userProperties;

    public BasicServerEndpointConfig(Class<?> endpointClass, String path)
    {
        this.endpointClass = endpointClass;
        this.path = path;

        this.decoders = new ArrayList<>();
        this.encoders = new ArrayList<>();
        this.subprotocols = new ArrayList<>();
        this.extensions = new ArrayList<>();
        this.userProperties = new HashMap<>();
        this.configurator = BasicServerEndpointConfigurator.INSTANCE;
    }

    public BasicServerEndpointConfig(ServerEndpointConfig copy)
    {
        this.endpointClass = copy.getEndpointClass();
        this.path = copy.getPath();

        this.decoders = new ArrayList<>(copy.getDecoders());
        this.encoders = new ArrayList<>(copy.getEncoders());
        this.subprotocols = new ArrayList<>(copy.getSubprotocols());
        this.extensions = new ArrayList<>(copy.getExtensions());
        this.userProperties = new HashMap<>(copy.getUserProperties());
        if (copy.getConfigurator() != null)
        {
            this.configurator = copy.getConfigurator();
        }
        else
        {
            this.configurator = BasicServerEndpointConfigurator.INSTANCE;
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
    public ServerEndpointConfig.Configurator getConfigurator()
    {
        return configurator;
    }
}
