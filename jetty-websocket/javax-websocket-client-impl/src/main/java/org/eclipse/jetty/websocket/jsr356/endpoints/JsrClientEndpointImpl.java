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
        JsrClientMetadata metadata = cache.get(endpointClass);
        if (metadata == null)
        {
            metadata = new JsrClientMetadata(endpointClass);
            AnnotatedEndpointScanner scanner = new AnnotatedEndpointScanner(metadata);
            scanner.scan();
            cache.put(endpointClass,metadata);
        }

        // The potential decoders

        if (config != null)
        {

        }
        return new JsrClientAnnotatedEventDriver(container,policy,endpoint,metadata);
    }

    @Override
    public String describeRule()
    {
        return "class is annotated with @" + ClientEndpoint.class.getName();
    }

    @Override
    public boolean supports(Object websocket)
    {
        ClientEndpoint anno = websocket.getClass().getAnnotation(ClientEndpoint.class);
        return (anno != null);
    }
}
