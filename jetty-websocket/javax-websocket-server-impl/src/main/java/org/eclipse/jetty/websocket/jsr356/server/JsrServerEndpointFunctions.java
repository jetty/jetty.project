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

import java.util.Map;
import java.util.concurrent.Executor;

import javax.websocket.DecodeException;
import javax.websocket.EndpointConfig;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.jsr356.function.JsrEndpointFunctions;

public class JsrServerEndpointFunctions extends JsrEndpointFunctions
{
    public JsrServerEndpointFunctions(Object endpoint, WebSocketPolicy policy, Executor executor,
                                      AvailableEncoders encoders, AvailableDecoders decoders,
                                      Map<String, String> uriParams, EndpointConfig endpointConfig)
    {
        super(endpoint, policy, executor, encoders, decoders, uriParams, endpointConfig);
    }
    
    /**
     * Generic discovery of annotated endpoint functions.
     *
     * @param endpoint the endpoint object
     */
    @SuppressWarnings("Duplicates")
    protected void discoverAnnotatedEndpointFunctions(Object endpoint)
    {
        Class<?> endpointClass = endpoint.getClass();
        
        // Use the JSR/Server annotation
        ServerEndpoint websocket = endpointClass.getAnnotation(ServerEndpoint.class);
        
        if (websocket != null)
        {
            encoders.registerAll(websocket.encoders());
            decoders.registerAll(websocket.decoders());
            
            // From here, the discovery of endpoint method is standard across
            // both JSR356/Client and JSR356/Server endpoints
            try
            {
                discoverJsrAnnotatedEndpointFunctions(endpoint);
                return;
            }
            catch (DecodeException e)
            {
                throw new InvalidWebSocketException("Cannot instantiate WebSocket", e);
            }
        }
        
        // Not a ServerEndpoint, let the ClientEndpoint test proceed
        super.discoverAnnotatedEndpointFunctions(endpoint);
    }
    
}
