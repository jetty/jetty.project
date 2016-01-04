//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverImpl;

public class JsrEndpointImpl implements EventDriverImpl
{
    @Override
    public EventDriver create(Object websocket, WebSocketPolicy policy)
    {
        if (!(websocket instanceof EndpointInstance))
        {
            throw new IllegalStateException(String.format("Websocket %s must be an %s",websocket.getClass().getName(),EndpointInstance.class.getName()));
        }

        return new JsrEndpointEventDriver(policy,(EndpointInstance)websocket);
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
