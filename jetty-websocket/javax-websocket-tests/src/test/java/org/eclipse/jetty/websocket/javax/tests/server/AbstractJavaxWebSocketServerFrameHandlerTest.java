//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.tests.server;

import java.util.HashMap;
import java.util.Map;

import javax.websocket.EndpointConfig;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.javax.client.EmptyClientEndpointConfig;
import org.eclipse.jetty.websocket.javax.common.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.javax.common.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.javax.server.JavaxWebSocketServerContainer;
import org.eclipse.jetty.websocket.servlet.WebSocketMapping;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

public abstract class AbstractJavaxWebSocketServerFrameHandlerTest
{
    private static Server server;
    protected static ServletContextHandler context;
    protected static JavaxWebSocketServerContainer container;

    @BeforeAll
    public static void initContainer() throws Exception
    {
        server = new Server();
        context = new ServletContextHandler();
        server.setHandler(context);

        WebSocketMapping factory = new WebSocketMapping();
        HttpClient httpClient = new HttpClient();

        container = new JavaxWebSocketServerContainer(factory, httpClient, server.getThreadPool());
        container.addBean(httpClient, true);
        container.addBean(factory, true);

        server.addBean(container, true);
        server.start();
    }

    @AfterAll
    public static void stopContainer() throws Exception
    {
        container.stop();
        server.stop();
    }

    protected AvailableEncoders encoders;
    protected AvailableDecoders decoders;
    protected Map<String, String> uriParams = new HashMap<>();
    protected EndpointConfig endpointConfig;

    public AbstractJavaxWebSocketServerFrameHandlerTest()
    {
        endpointConfig = new EmptyClientEndpointConfig();
        encoders = new AvailableEncoders(endpointConfig);
        decoders = new AvailableDecoders(endpointConfig);
        uriParams = new HashMap<>();
    }
}
