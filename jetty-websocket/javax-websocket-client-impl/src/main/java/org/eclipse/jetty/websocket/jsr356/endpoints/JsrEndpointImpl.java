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

import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverImpl;

public class JsrEndpointImpl implements EventDriverImpl
{
    @Override
    public EventDriver create(Object websocket, WebSocketPolicy policy)
    {
        Object endpoint = websocket;
        EndpointConfig config = null;

        if (endpoint instanceof ConfiguredEndpoint)
        {
            // unwrap
            ConfiguredEndpoint ce = (ConfiguredEndpoint)websocket;
            endpoint = ce.getEndpoint();
            config = ce.getConfig();
        }

        return new JsrEndpointEventDriver(policy,(Endpoint)endpoint,config);
    }

    @Override
    public String describeRule()
    {
        return "class extends " + javax.websocket.Endpoint.class.getName();
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

        return (endpoint instanceof javax.websocket.Endpoint);
    }
}
