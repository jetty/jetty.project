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

package org.eclipse.jetty.websocket.jsr356;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.Extension;

public class SimpleClientEndpointConfig implements ClientEndpointConfig
{
    public static class DummyConfigurator extends ClientEndpointConfig.Configurator
    {
        public static final DummyConfigurator INSTANCE = new DummyConfigurator();
        /* do nothing */
    }

    private Configurator configurator;
    private List<Class<? extends Decoder>> decoders;
    private List<Class<? extends Encoder>> encoders;
    private List<Extension> extensions;
    private List<String> preferredSubprotocols;
    private Map<String, Object> userProperties;

    public SimpleClientEndpointConfig()
    {
        this.configurator = DummyConfigurator.INSTANCE;
        this.decoders = new ArrayList<>();
        this.encoders = new ArrayList<>();
        this.extensions = new ArrayList<>();
        this.preferredSubprotocols = new ArrayList<>();
        this.userProperties = new HashMap<>();
    }

    public void addDecoder(Class<? extends Decoder> decoderClass)
    {
        this.decoders.add(decoderClass);
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