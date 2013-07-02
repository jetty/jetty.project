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

package org.eclipse.jetty.websocket.jsr356.client;

import javax.websocket.ClientEndpoint;
import javax.websocket.DeploymentException;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverImpl;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrEvents;
import org.eclipse.jetty.websocket.jsr356.endpoints.ConfiguredEndpoint;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrAnnotatedEventDriver;

/**
 * Event Driver for classes annotated with &#064;{@link ClientEndpoint}
 */
public class JsrClientEndpointImpl implements EventDriverImpl
{
    private ClientContainer container;

    public JsrClientEndpointImpl(ClientContainer container)
    {
        this.container = container;
    }

    @Override
    public EventDriver create(Object websocket, WebSocketPolicy policy) throws DeploymentException
    {
        Object endpoint = websocket;
        if (websocket instanceof ConfiguredEndpoint)
        {
            ConfiguredEndpoint ce = (ConfiguredEndpoint)websocket;
            endpoint = ce.getEndpoint();
            // Classes annotated with @ClientEndpoint will have their ClientEndpointConfig
            // built up from the information present in the annotations, any provided config will be ignored
        }

        Class<?> endpointClass = endpoint.getClass();
        // Get the base metadata for this class
        JsrClientMetadata basemetadata = container.getClientEndpointMetadata(endpointClass);

        // At this point we have a base metadata, now we need to copy it for
        // this specific instance of the WebSocket Endpoint (as we will be
        // modifying the metadata)
        JsrEvents events = new JsrEvents(basemetadata); // copy constructor.

        // Create copy of base config
        AnnotatedClientEndpointConfig config = basemetadata.getConfig();
        return new JsrAnnotatedEventDriver(policy,endpoint,events,config);
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
