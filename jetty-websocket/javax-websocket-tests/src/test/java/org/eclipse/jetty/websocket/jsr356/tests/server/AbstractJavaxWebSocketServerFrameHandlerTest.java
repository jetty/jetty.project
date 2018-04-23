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

package org.eclipse.jetty.websocket.jsr356.tests.server;

import java.util.HashMap;
import java.util.Map;

import javax.websocket.EndpointConfig;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.common.WebSocketContainerContext;
import org.eclipse.jetty.websocket.jsr356.client.EmptyClientEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.jsr356.server.JavaxWebSocketServerContainer;
import org.eclipse.jetty.websocket.servlet.MappedWebSocketServletNegotiator;
import org.eclipse.jetty.websocket.servlet.NativeWebSocketConfiguration;
import org.eclipse.jetty.websocket.servlet.ServletContextWebSocketContainer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.ExpectedException;

public abstract class AbstractJavaxWebSocketServerFrameHandlerTest
{
    private static Server server;
    protected static ServletContextHandler context;
    protected static JavaxWebSocketServerContainer container;
    
    @BeforeClass
    public static void initContainer() throws Exception
    {
        server = new Server();
        context = new ServletContextHandler();
        server.setHandler(context);

        WebSocketContainerContext containerContext = ServletContextWebSocketContainer.get(context.getServletContext());
        MappedWebSocketServletNegotiator mappedNegotiator = new NativeWebSocketConfiguration(context.getServletContext());
        HttpClient httpClient = new HttpClient();

        container = new JavaxWebSocketServerContainer(containerContext, mappedNegotiator, httpClient);
        container.addBean(httpClient, true);
        container.addBean(mappedNegotiator, true);
        container.addBean(containerContext, true);

        server.addBean(container);
        server.start();
    }
    
    @AfterClass
    public static void stopContainer() throws Exception
    {
        container.stop();
        server.stop();
    }
    
    @Rule
    public ExpectedException expectedException = ExpectedException.none();

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
