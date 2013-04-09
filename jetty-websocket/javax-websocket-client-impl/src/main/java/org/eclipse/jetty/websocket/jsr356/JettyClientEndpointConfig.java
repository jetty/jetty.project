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

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.Decoder;
import javax.websocket.DeploymentException;
import javax.websocket.Encoder;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;

public class JettyClientEndpointConfig implements ClientEndpointConfig
{
    public static class NoopConfigurator extends ClientEndpointConfig.Configurator
    {
        public static final NoopConfigurator INSTANCE = new NoopConfigurator();

        @Override
        public void afterResponse(HandshakeResponse hr)
        {
            // do nothing
        }

        @Override
        public void beforeRequest(Map<String, List<String>> headers)
        {
            // do nothing
        }
    }

    private Configurator configurator;
    private List<Class<? extends Decoder>> decoders;
    private List<Class<? extends Encoder>> encoders;
    private List<String> subprotocols;
    private List<Extension> extensions;
    private Map<String, Object> userProperties;

    public JettyClientEndpointConfig()
    {
        decoders = new ArrayList<>();
        encoders = new ArrayList<>();
        subprotocols = new ArrayList<>();
        extensions = new ArrayList<>();
        userProperties = new HashMap<>();
    }

    public JettyClientEndpointConfig(ClientEndpoint anno) throws DeploymentException
    {
        this();
        addAll(anno.decoders(),this.decoders);
        addAll(anno.encoders(),this.encoders);
        addAll(anno.subprotocols(),this.subprotocols);
        if (anno.configurator() == null)
        {
            this.configurator = NoopConfigurator.INSTANCE;
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

    /**
     * Copy Constructor
     * 
     * @param copy
     *            the endpoint configuration to copy
     * @throws DeploymentException
     */
    public JettyClientEndpointConfig(JettyClientEndpointConfig copy) throws DeploymentException
    {
        this();
        this.decoders.addAll(copy.decoders);
        this.encoders.addAll(copy.encoders);
        this.subprotocols.addAll(copy.subprotocols);
        this.extensions.addAll(copy.extensions);
        this.userProperties.putAll(copy.userProperties);
        if (copy.configurator instanceof NoopConfigurator)
        {
            this.configurator = NoopConfigurator.INSTANCE;
        }
        else
        {
            Class<? extends Configurator> configuratorClass = copy.configurator.getClass();
            try
            {
                this.configurator = configuratorClass.newInstance();
            }
            catch (InstantiationException | IllegalAccessException e)
            {
                StringBuilder err = new StringBuilder();
                err.append("Unable to instantiate ClientEndpoint.configurator() of ");
                err.append(configuratorClass);
                throw new DeploymentException(err.toString(),e);
            }
        }
    }

    private <T> void addAll(T[] arr, List<T> lst)
    {
        if (arr == null)
        {
            return;
        }
        for (T t : arr)
        {
            lst.add(t);
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
    public List<Extension> getExtensions()
    {
        return extensions;
    }

    @Override
    public List<String> getPreferredSubprotocols()
    {
        return subprotocols;
    }

    @Override
    public Map<String, Object> getUserProperties()
    {
        return userProperties;
    }
}
