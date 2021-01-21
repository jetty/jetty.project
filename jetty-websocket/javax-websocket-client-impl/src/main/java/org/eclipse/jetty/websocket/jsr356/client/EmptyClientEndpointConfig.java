//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.jsr356.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.Extension;

public class EmptyClientEndpointConfig implements ClientEndpointConfig
{
    private final List<Class<? extends Decoder>> decoders;
    private final List<Class<? extends Encoder>> encoders;
    private final List<Extension> extensions;
    private final List<String> preferredSubprotocols;
    private final Configurator configurator;
    private Map<String, Object> userProperties;

    public EmptyClientEndpointConfig()
    {
        this.decoders = new ArrayList<>();
        this.encoders = new ArrayList<>();
        this.preferredSubprotocols = new ArrayList<>();
        this.extensions = new ArrayList<>();
        this.userProperties = new HashMap<>();
        this.configurator = EmptyConfigurator.INSTANCE;
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
    public List<Extension> getExtensions()
    {
        return extensions;
    }

    @Override
    public List<String> getPreferredSubprotocols()
    {
        return preferredSubprotocols;
    }

    @Override
    public Map<String, Object> getUserProperties()
    {
        return userProperties;
    }
}
