//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketContainer;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketFrameHandlerMetadata;

public class JavaxWebSocketServerFrameHandlerFactory extends JavaxWebSocketFrameHandlerFactory
{
    public JavaxWebSocketServerFrameHandlerFactory(JavaxWebSocketContainer container)
    {
        super(container);
    }

    public JavaxWebSocketFrameHandlerMetadata createMetadata(Class<?> endpointClass)
    {
        if (javax.websocket.Endpoint.class.isAssignableFrom(endpointClass))
        {
            return createEndpointMetadata(endpointClass);
        }

        ServerEndpoint websocket = endpointClass.getAnnotation(javax.websocket.server.ServerEndpoint.class);
        if (websocket != null)
        {
            return createServerEndpointMetadata(websocket, endpointClass);
        }

        // Unrecognized
        return null;
    }

    private JavaxWebSocketFrameHandlerMetadata createServerEndpointMetadata(javax.websocket.server.ServerEndpoint anno, Class<?> endpointClass)
    {
        JavaxWebSocketFrameHandlerMetadata metadata = new JavaxWebSocketFrameHandlerMetadata();

        metadata.setServerConfigurator(anno.configurator());
        metadata.setDecoders(anno.decoders());
        metadata.setEncoders(anno.encoders());
        metadata.setSubProtocols(anno.subprotocols());

        return discoverJavaxFrameHandlerMetadata(endpointClass, metadata);
    }
}
