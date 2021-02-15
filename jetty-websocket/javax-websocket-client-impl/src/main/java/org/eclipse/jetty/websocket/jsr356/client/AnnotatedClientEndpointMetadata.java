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

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointMetadata;

public class AnnotatedClientEndpointMetadata extends AnnotatedEndpointMetadata<ClientEndpoint, ClientEndpointConfig>
{
    private final ClientEndpoint endpoint;
    private final AnnotatedClientEndpointConfig config;

    public AnnotatedClientEndpointMetadata(ClientContainer container, Class<?> websocket)
    {
        super(websocket);

        ClientEndpoint anno = websocket.getAnnotation(ClientEndpoint.class);
        if (anno == null)
        {
            throw new InvalidWebSocketException(String.format("Unsupported WebSocket object [%s], missing @%s annotation", websocket.getName(),
                ClientEndpoint.class.getName()));
        }

        this.endpoint = anno;
        this.config = new AnnotatedClientEndpointConfig(anno);

        getDecoders().addAll(anno.decoders());
        getEncoders().addAll(anno.encoders());
    }

    @Override
    public ClientEndpoint getAnnotation()
    {
        return endpoint;
    }

    @Override
    public ClientEndpointConfig getConfig()
    {
        return config;
    }
}
