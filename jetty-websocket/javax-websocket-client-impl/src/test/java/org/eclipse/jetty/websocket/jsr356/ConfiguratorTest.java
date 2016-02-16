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

package org.eclipse.jetty.websocket.jsr356;

import static org.hamcrest.Matchers.notNullValue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;
import javax.websocket.HandshakeResponse;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests of {@link javax.websocket.ClientEndpointConfig.Configurator}
 */
public class ConfiguratorTest
{
    public class TrackingConfigurator extends ClientEndpointConfig.Configurator
    {
        public HandshakeResponse response;
        public Map<String, List<String>> request;

        @Override
        public void afterResponse(HandshakeResponse hr)
        {
            this.response = hr;
        }

        @Override
        public void beforeRequest(Map<String, List<String>> headers)
        {
            this.request = headers;
        }
    }

    private static Server server;
    private static EchoHandler handler;
    private static URI serverUri;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        handler = new EchoHandler();

        ContextHandler context = new ContextHandler();
        context.setContextPath("/");
        context.setHandler(handler);
        server.setHandler(context);

        // Start Server
        server.start();

        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverUri = new URI(String.format("ws://%s:%d/",host,port));
    }

    @AfterClass
    public static void stopServer()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }
    }

    @Test
    public void testEndpointHandshakeInfo() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        EndpointEchoClient echoer = new EndpointEchoClient();

        // Build Config
        ClientEndpointConfig.Builder cfgbldr = ClientEndpointConfig.Builder.create();
        TrackingConfigurator configurator = new TrackingConfigurator();
        cfgbldr.configurator(configurator);
        ClientEndpointConfig config = cfgbldr.build();

        // Connect
        Session session = container.connectToServer(echoer,config,serverUri);

        // Send Simple Message
        session.getBasicRemote().sendText("Echo");

        // Wait for echo
        echoer.textCapture.messageQueue.awaitMessages(1,1000,TimeUnit.MILLISECONDS);

        // Validate client side configurator use
        Assert.assertThat("configurator.request",configurator.request,notNullValue());
        Assert.assertThat("configurator.response",configurator.response,notNullValue());
    }
}
