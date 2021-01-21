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
import javax.websocket.DeploymentException;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverImpl;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrEvents;
import org.eclipse.jetty.websocket.jsr356.endpoints.EndpointInstance;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrAnnotatedEventDriver;

/**
 * Event Driver for classes annotated with &#064;{@link ClientEndpoint}
 */
public class JsrClientEndpointImpl implements EventDriverImpl
{
    @Override
    public EventDriver create(Object websocket, WebSocketPolicy policy) throws DeploymentException
    {
        if (!(websocket instanceof EndpointInstance))
        {
            throw new IllegalStateException(String.format("Websocket %s must be an %s", websocket.getClass().getName(), EndpointInstance.class.getName()));
        }

        EndpointInstance ei = (EndpointInstance)websocket;
        AnnotatedClientEndpointMetadata metadata = (AnnotatedClientEndpointMetadata)ei.getMetadata();
        JsrEvents<ClientEndpoint, ClientEndpointConfig> events = new JsrEvents<>(metadata);

        // Handle @OnMessage maxMessageSizes
        int maxBinaryMessage = getMaxMessageSize(policy.getMaxBinaryMessageSize(), metadata.maxBinaryMessageSize());
        int maxTextMessage = getMaxMessageSize(policy.getMaxTextMessageSize(), metadata.maxTextMessageSize());

        policy.setMaxBinaryMessageSize(maxBinaryMessage);
        policy.setMaxTextMessageSize(maxTextMessage);

        return new JsrAnnotatedEventDriver(policy, ei, events);
    }

    @Override
    public String describeRule()
    {
        return "class is annotated with @" + ClientEndpoint.class.getName();
    }

    private int getMaxMessageSize(int defaultMaxMessageSize, long maxMessageSize)
    {
        if (maxMessageSize >= 1)
        {
            return (int)maxMessageSize;
        }
        return defaultMaxMessageSize;
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

        ClientEndpoint anno = endpoint.getClass().getAnnotation(ClientEndpoint.class);
        return (anno != null);
    }
}
