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
import java.util.List;

import javax.websocket.Decoder;
import javax.websocket.DeploymentException;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.jsr356.DecoderFactory;
import org.eclipse.jetty.websocket.jsr356.EncoderFactory;
import org.eclipse.jetty.websocket.jsr356.annotations.IJsrParamId;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrMetadata;

public class JsrServerMetadata extends JsrMetadata<ServerEndpoint>
{
    private final ServerEndpoint endpoint;
    private final AnnotatedServerEndpointConfig config;
    private final DecoderFactory decoders;
    private final EncoderFactory encoders;

    protected JsrServerMetadata(ServerContainer container, Class<?> websocket) throws DeploymentException
    {
        super(websocket);

        ServerEndpoint anno = websocket.getAnnotation(ServerEndpoint.class);
        if (anno == null)
        {
            throw new InvalidWebSocketException("Unsupported WebSocket object, missing @" + ServerEndpoint.class + " annotation");
        }

        this.endpoint = anno;
        this.config = new AnnotatedServerEndpointConfig(websocket,anno);
        this.decoders = new DecoderFactory(container.getDecoderFactory());
        this.encoders = new EncoderFactory(container.getEncoderFactory());
        
        this.decoders.registerAll(anno.decoders());
        this.encoders.registerAll(anno.encoders());
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
    protected List<Class<? extends Decoder>> getConfiguredDecoders()
    {
        return config.getDecoders();
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
