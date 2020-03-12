//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.jakarta.tests.server;

import java.util.HashMap;
import java.util.Map;

import jakarta.websocket.EndpointConfig;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jakarta.client.BasicClientEndpointConfig;
import org.eclipse.jetty.websocket.jakarta.common.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jakarta.common.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.jakarta.server.internal.JakartaWebSocketServerContainer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

public abstract class AbstractJakartaWebSocketServerFrameHandlerTest
{
    private static Server server;
    protected static ServletContextHandler context;
    protected static JakartaWebSocketServerContainer container;

    @BeforeAll
    public static void initContainer() throws Exception
    {
        server = new Server();
        context = new ServletContextHandler();
        server.setHandler(context);
        JakartaWebSocketServletContainerInitializer.configure(context, null);
        server.start();
        container = JakartaWebSocketServerContainer.getContainer(context.getServletContext());
    }

    @AfterAll
    public static void stopContainer() throws Exception
    {
        server.stop();
    }

    protected AvailableEncoders encoders;
    protected AvailableDecoders decoders;
    protected Map<String, String> uriParams;
    protected EndpointConfig endpointConfig;

    public AbstractJakartaWebSocketServerFrameHandlerTest()
    {
        endpointConfig = new BasicClientEndpointConfig();
        encoders = new AvailableEncoders(endpointConfig);
        decoders = new AvailableDecoders(endpointConfig);
        uriParams = new HashMap<>();
    }
}
