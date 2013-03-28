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

package org.eclipse.jetty.websocket.jsr356.endpoints;

import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverImpl;
import org.eclipse.jetty.websocket.jsr356.JettyWebSocketContainer;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrEvents;

public class JsrClientEndpointImpl implements EventDriverImpl
{
    private ConcurrentHashMap<Class<?>, JsrClientMetadata> cache = new ConcurrentHashMap<>();
    private JettyWebSocketContainer container;

    public JsrClientEndpointImpl(JettyWebSocketContainer container)
    {
        this.container = container;
    }

    @Override
    public EventDriver create(Object websocket, WebSocketPolicy policy)
    {
        Object endpoint = websocket;
        ClientEndpointConfig config = null;
        if (websocket instanceof ConfiguredEndpoint)
        {
            ConfiguredEndpoint ce = (ConfiguredEndpoint)websocket;
            endpoint = ce.getEndpoint();
            config = (ClientEndpointConfig)ce.getConfig();
        }

        Class<?> endpointClass = endpoint.getClass();
        // Get the base metadata for this class
        JsrClientMetadata basemetadata = cache.get(endpointClass);
        if (basemetadata == null)
        {
            basemetadata = new JsrClientMetadata(endpointClass);
            AnnotatedEndpointScanner scanner = new AnnotatedEndpointScanner(basemetadata);
            scanner.scan();
            cache.put(endpointClass,basemetadata);
        }

        // At this point we have a base metadata, now we need to copy it for
        // this specific instance of the WebSocket Endpoint (as we will be
        // modifying the metadata)
        JsrEvents events = new JsrEvents(basemetadata);
        return new JsrClientAnnotatedEventDriver(container,policy,endpoint,events);
    }

    @Override
    public String describeRule()
    {
        return "class is annotated with @" + ClientEndpoint.class.getName();
    }

    @Override
    public boolean supports(Object websocket)
    {
        Object endpoint = websocket;

        if (endpoint instanceof ConfiguredEndpoint)
        {
            // unwrap
            ConfiguredEndpoint ce = (ConfiguredEndpoint)websocket;
            endpoint = ce.getEndpoint();
        }

        ClientEndpoint anno = endpoint.getClass().getAnnotation(ClientEndpoint.class);
        return (anno != null);
    }
}
