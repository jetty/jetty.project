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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.Extension;

/**
 * The DefaultClientEndpointConfig to use.
 */
public class DefaultClientEndpointConfig implements ClientEndpointConfig
{
    public static class DefaultClientEndpointConfigurator extends ClientEndpointConfig.Configurator
    {
        public static final DefaultClientEndpointConfigurator INSTANCE = new DefaultClientEndpointConfigurator();
    }

    private List<Class<? extends Decoder>> decoders;
    private List<Class<? extends Encoder>> encoders;
    private List<Extension> extensions;
    private Map<String, Object> userProperties;
    private List<String> preferredSubprotocols;

    private DefaultClientEndpointConfig()
    {
        this.extensions = new ArrayList<>();
        this.userProperties = new HashMap<>();
        this.preferredSubprotocols = new ArrayList<>();
    }

    /**
     * Constructor from annotation.
     * 
     * @param decoders
     *            the array of decoder classes on the annotation
     * @param encoders
     *            the array of encoder classes on the annotation
     */
    public DefaultClientEndpointConfig(Class<? extends Decoder>[] decoders, Class<? extends Encoder>[] encoders)
    {
        this();
        this.decoders = Collections.unmodifiableList(Arrays.asList(decoders));
        this.encoders = Collections.unmodifiableList(Arrays.asList(encoders));
    }

    @Override
    public Configurator getConfigurator()
    {
        return DefaultClientEndpointConfigurator.INSTANCE;
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
