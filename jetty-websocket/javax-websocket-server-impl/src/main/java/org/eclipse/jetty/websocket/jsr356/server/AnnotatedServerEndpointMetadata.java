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

import java.util.LinkedList;

import javax.websocket.DeploymentException;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.jsr356.annotations.IJsrParamId;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointMetadata;

public class AnnotatedServerEndpointMetadata extends AnnotatedEndpointMetadata<ServerEndpoint,ServerEndpointConfig> implements ServerEndpointMetadata
{
    private final ServerEndpoint endpoint;
    private final AnnotatedServerEndpointConfig config;

    protected AnnotatedServerEndpointMetadata(ServerContainer container, Class<?> websocket) throws DeploymentException
    {
        super(websocket);

        ServerEndpoint anno = websocket.getAnnotation(ServerEndpoint.class);
        if (anno == null)
        {
            throw new InvalidWebSocketException("Unsupported WebSocket object, missing @" + ServerEndpoint.class + " annotation");
        }

        this.endpoint = anno;
        this.config = new AnnotatedServerEndpointConfig(websocket,anno);
        
        getDecoders().addAll(anno.decoders());
        getEncoders().addAll(anno.encoders());
    }

    @Override
    public void customizeParamsOnClose(LinkedList<IJsrParamId> params)
    {
        params.addFirst(JsrParamPath.INSTANCE);
    }

    @Override
    public void customizeParamsOnError(LinkedList<IJsrParamId> params)
    {
        params.addFirst(JsrParamPath.INSTANCE);
    }
    
    @Override
    public void customizeParamsOnOpen(LinkedList<IJsrParamId> params)
    {
        params.addFirst(JsrParamPath.INSTANCE);
    }

    @Override
    public ServerEndpoint getAnnotation()
    {
        return endpoint;
    }

    public AnnotatedServerEndpointConfig getConfig()
    {
        return config;
    }

    public String getPath()
    {
        return config.getPath();
    }
}
