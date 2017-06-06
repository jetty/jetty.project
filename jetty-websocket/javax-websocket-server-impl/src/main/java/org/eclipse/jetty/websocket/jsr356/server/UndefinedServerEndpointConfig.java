//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import javax.websocket.DeploymentException;
import javax.websocket.Encoder;
import javax.websocket.Extension;
import javax.websocket.server.ServerEndpointConfig;

public class UndefinedServerEndpointConfig implements ServerEndpointConfig
{
    private final List<Class<? extends Decoder>> decoders;
    private final List<Class<? extends Encoder>> encoders;
    private final List<Extension> extensions;
    private final List<String> subprotocols;
    private final ServerEndpointConfig.Configurator configurator;
    private final Class<?> endpointClass;
    private Map<String, Object> userProperties;
    
    public UndefinedServerEndpointConfig(Class<?> endpointClass)
    {
        this.endpointClass = endpointClass;
        this.decoders = new ArrayList<>();
        this.encoders = new ArrayList<>();
        this.subprotocols = new ArrayList<>();
        this.extensions = new ArrayList<>();
        this.userProperties = new HashMap<>();
        this.configurator = new ContainerDefaultConfigurator();
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
        throw new RuntimeException("Using an UndefinedServerEndpointConfig");
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
