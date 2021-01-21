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

package org.eclipse.jetty.websocket.jsr356.server;

import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverImpl;
import org.eclipse.jetty.websocket.jsr356.endpoints.EndpointInstance;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrEndpointEventDriver;

public class JsrServerExtendsEndpointImpl implements EventDriverImpl
{
    @Override
    public EventDriver create(Object websocket, WebSocketPolicy policy)
    {
        if (!(websocket instanceof EndpointInstance))
        {
            throw new IllegalStateException(String.format("Websocket %s must be an %s", websocket.getClass().getName(), EndpointInstance.class.getName()));
        }

        EndpointInstance ei = (EndpointInstance)websocket;
        JsrEndpointEventDriver driver = new JsrEndpointEventDriver(policy, ei);

        ServerEndpointConfig config = (ServerEndpointConfig)ei.getConfig();
        if (config instanceof PathParamServerEndpointConfig)
        {
            PathParamServerEndpointConfig ppconfig = (PathParamServerEndpointConfig)config;
            driver.setPathParameters(ppconfig.getPathParamMap());
        }

        return driver;
    }

    @Override
    public String describeRule()
    {
        return "class extends " + javax.websocket.Endpoint.class.getName();
    }

    @Override
    public boolean supports(Object websocket)
    {
        if (!(websocket instanceof EndpointInstance))
        {
            return false;
        }

        EndpointInstance ei = (EndpointInstance)websocket;
        Object endpoint = ei.getEndpoint();

        return (endpoint instanceof javax.websocket.Endpoint);
    }
}
