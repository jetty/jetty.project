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

import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverImpl;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrEvents;
import org.eclipse.jetty.websocket.jsr356.endpoints.ConfiguredEndpoint;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrAnnotatedEventDriver;

/**
 * Event Driver for classes annotated with &#064;{@link ServerEndpoint}
 */
public class JsrServerEndpointImpl implements EventDriverImpl
{
    private ServerContainer container;

    public JsrServerEndpointImpl(ServerContainer container)
    {
        this.container = container;
    }

    @Override
    public EventDriver create(Object websocket, WebSocketPolicy policy) throws Throwable
    {
        Object endpoint = websocket;
        if (websocket instanceof ConfiguredEndpoint)
        {
            ConfiguredEndpoint ce = (ConfiguredEndpoint)websocket;
            endpoint = ce.getEndpoint();
            // Classes annotated with @ServerEndpoint cannot be created with
            // an external ServerEndpointConfig, this information MUST come
            // from the @ServerEndpoint annotation.
            if (ce.getConfig() != null)
            {
                throw new IllegalStateException("Cannot create @ServerEndpoint websocket with an external EndpointConfig");
            }
        }

        Class<?> endpointClass = endpoint.getClass();
        // Get the base metadata for this class
        JsrServerMetadata basemetadata = container.getServerEndpointMetadata(endpointClass);

        // At this point we have a base metadata, now we need to copy it for
        // this specific instance of the WebSocket Endpoint (as we will be
        // modifying the metadata)
        JsrEvents events = new JsrEvents(basemetadata); // copy constructor.

        // Create copy of base config
        ServerEndpointConfig config = basemetadata.getEndpointConfigCopy();
        return new JsrAnnotatedEventDriver(policy,endpoint,events,config);
    }

    @Override
    public String describeRule()
    {
        return "class is annotated with @" + ServerEndpoint.class.getName();
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

        ServerEndpoint anno = endpoint.getClass().getAnnotation(ServerEndpoint.class);
        return (anno != null);
    }
}
