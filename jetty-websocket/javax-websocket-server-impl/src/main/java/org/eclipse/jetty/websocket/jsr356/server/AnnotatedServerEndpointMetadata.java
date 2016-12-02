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

import java.util.LinkedList;

import javax.websocket.DeploymentException;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointMetadata;
import org.eclipse.jetty.websocket.jsr356.annotations.IJsrParamId;

public class AnnotatedServerEndpointMetadata extends AnnotatedEndpointMetadata<ServerEndpoint,ServerEndpointConfig> implements ServerEndpointMetadata
{
    private final ServerEndpoint endpoint;
    private final AnnotatedServerEndpointConfig config;

    protected AnnotatedServerEndpointMetadata(WebSocketContainerScope containerScope, Class<?> websocket, ServerEndpointConfig baseConfig) throws DeploymentException
    {
        super(websocket);

        ServerEndpoint anno = websocket.getAnnotation(ServerEndpoint.class);
        if (anno == null)
        {
            throw new InvalidWebSocketException("Unsupported WebSocket object, missing @" + ServerEndpoint.class + " annotation");
        }

        this.endpoint = anno;
        this.config = new AnnotatedServerEndpointConfig(containerScope,websocket,anno,baseConfig);
        
        getDecoders().addAll(anno.decoders());
        getEncoders().addAll(anno.encoders());
    }

    @Override
    public void customizeParamsOnClose(LinkedList<IJsrParamId> params)
    {
        super.customizeParamsOnClose(params);
        params.addFirst(JsrPathParamId.INSTANCE);
    }

    @Override
    public void customizeParamsOnError(LinkedList<IJsrParamId> params)
    {
        super.customizeParamsOnError(params);
        params.addFirst(JsrPathParamId.INSTANCE);
    }
    
    @Override
    public void customizeParamsOnOpen(LinkedList<IJsrParamId> params)
    {
        super.customizeParamsOnOpen(params);
        params.addFirst(JsrPathParamId.INSTANCE);
    }
    
    @Override
    public void customizeParamsOnMessage(LinkedList<IJsrParamId> params)
    {
        super.customizeParamsOnMessage(params);
        params.addFirst(JsrPathParamId.INSTANCE);
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

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("AnnotatedServerEndpointMetadata[endpoint=");
        builder.append(endpoint);
        builder.append(",config=");
        builder.append(config);
        builder.append("]");
        return builder.toString();
    }
}
