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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.websocket.Decoder;
import javax.websocket.DeploymentException;
import javax.websocket.Encoder;
import javax.websocket.Extension;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

public class JettyServerEndpointConfig implements ServerEndpointConfig
{
    public static class BaseConfigurator extends ServerEndpointConfig.Configurator
    {
        public static final BaseConfigurator INSTANCE = new BaseConfigurator();
    }

    private final Class<?> endpointClass;
    private final String path;
    private Configurator configurator;
    private List<Class<? extends Decoder>> decoders;
    private List<Class<? extends Encoder>> encoders;
    private List<String> subprotocols;
    private List<Extension> extensions;
    private Map<String, Object> userProperties;

    public JettyServerEndpointConfig(Class<?> endpointClass, String path)
    {
        this.endpointClass = endpointClass;
        this.path = path;

        this.configurator = new BaseConfigurator();
        this.decoders = new ArrayList<>();
        this.encoders = new ArrayList<>();
        this.subprotocols = new ArrayList<>();
        this.extensions = new ArrayList<>();
        this.userProperties = new HashMap<>();
    }

    /**
     * Copy Constructor
     * 
     * @param copy
     *            the endpoint configuration to copy
     * @throws DeploymentException
     */
    public JettyServerEndpointConfig(JettyServerEndpointConfig copy) throws DeploymentException
    {
        this(copy.endpointClass,copy.path);
        this.decoders.addAll(copy.decoders);
        this.encoders.addAll(copy.encoders);
        this.subprotocols.addAll(copy.subprotocols);
        this.extensions.addAll(copy.extensions);
        this.userProperties.putAll(copy.userProperties);
        if (copy.configurator instanceof BaseConfigurator)
        {
            this.configurator = BaseConfigurator.INSTANCE;
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
                err.append("Unable to instantiate ServerEndpointConfig.Configurator of ");
                err.append(configuratorClass);
                throw new DeploymentException(err.toString(),e);
            }
        }
    }

    public JettyServerEndpointConfig(ServerEndpoint anno) throws DeploymentException
    {
        this(anno.getClass(),anno.value());
        addAll(anno.decoders(),this.decoders);
        addAll(anno.encoders(),this.encoders);
        addAll(anno.subprotocols(),this.subprotocols);
        // no extensions declared in annotation
        // no userProperties in annotation
        if (anno.configurator() == null)
        {
            this.configurator = BaseConfigurator.INSTANCE;
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
                err.append("Unable to instantiate ServerEndpoint.configurator() of ");
                err.append(anno.configurator().getName());
                err.append(" defined as annotation in ");
                err.append(anno.getClass().getName());
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
}
