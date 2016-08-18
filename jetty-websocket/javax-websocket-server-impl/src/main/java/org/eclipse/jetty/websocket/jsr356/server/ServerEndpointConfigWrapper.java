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

import java.util.List;
import java.util.Map;

import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.Extension;
import javax.websocket.server.ServerEndpointConfig;

public abstract class ServerEndpointConfigWrapper implements ServerEndpointConfig
{
    private final ServerEndpointConfig delegate;
    
    public ServerEndpointConfigWrapper(ServerEndpointConfig delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public List<Class<? extends Encoder>> getEncoders()
    {
        return delegate.getEncoders();
    }

    @Override
    public List<Class<? extends Decoder>> getDecoders()
    {
        return delegate.getDecoders();
    }

    @Override
    public Map<String, Object> getUserProperties()
    {
        return delegate.getUserProperties();
    }

    @Override
    public Class<?> getEndpointClass()
    {
        return delegate.getEndpointClass();
    }

    @Override
    public String getPath()
    {
        return delegate.getPath();
    }

    @Override
    public List<String> getSubprotocols()
    {
        return delegate.getSubprotocols();
    }
    
    @Override
    public List<Extension> getExtensions()
    {
        return delegate.getExtensions();
    }

    @Override
    public Configurator getConfigurator()
    {
        return delegate.getConfigurator();
    }
}
