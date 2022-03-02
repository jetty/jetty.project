//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server;

import java.util.HashMap;
import java.util.Map;

import jakarta.websocket.EndpointConfig;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.websocket.jakarta.client.internal.BasicClientEndpointConfig;
import org.eclipse.jetty.ee9.websocket.jakarta.common.decoders.AvailableDecoders;
import org.eclipse.jetty.ee9.websocket.jakarta.common.encoders.AvailableEncoders;
import org.eclipse.jetty.ee9.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee9.websocket.jakarta.server.internal.JakartaWebSocketServerContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
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
    private WebSocketComponents components = new WebSocketComponents();

    public AbstractJakartaWebSocketServerFrameHandlerTest()
    {
        endpointConfig = new BasicClientEndpointConfig();
        encoders = new AvailableEncoders(endpointConfig, components);
        decoders = new AvailableDecoders(endpointConfig, components);
        uriParams = new HashMap<>();
    }
}
