//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketOverHTTP2Test
{
    private Server server;
    private ServerConnector connector;

    @BeforeEach
    public void startServer() throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfiguration);
        connector = new ServerConnector(server, 1, 1, h1, h2c);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");
        context.addServlet(new ServletHolder(new JettyWebSocketServlet()
        {
            @Override
            protected void configure(JettyWebSocketServletFactory factory)
            {
                factory.addMapping("/ws/echo", (req, resp) -> new EchoSocket());
            }
        }), "/ws/*");
        JettyWebSocketServletContainerInitializer.initialize(context);

        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testWebSocketOverDynamicHTTP1() throws Exception
    {
        testWebSocketOverDynamicTransport(clientConnector -> HttpClientConnectionFactory.HTTP11);
    }

    @Test
    public void testWebSocketOverDynamicHTTP2() throws Exception
    {
        testWebSocketOverDynamicTransport(clientConnector -> new ClientConnectionFactoryOverHTTP2.H2C(new HTTP2Client(clientConnector)));
    }

    private void testWebSocketOverDynamicTransport(Function<ClientConnector, ClientConnectionFactory.Info> protocolFn) throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector, protocolFn.apply(clientConnector)));

        WebSocketClient wsClient = new WebSocketClient(httpClient);
        wsClient.start();

        EventSocket wsEndPoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/ws/echo");
        Session session = wsClient.connect(wsEndPoint, uri).get(5, TimeUnit.SECONDS);

        String text = "websocket";
        session.getRemote().sendString(text);

        String message = wsEndPoint.messageQueue.poll(5, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals(text, message);

        session.close(StatusCode.NORMAL, null);
        assertTrue(wsEndPoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertEquals(StatusCode.NORMAL, wsEndPoint.statusCode);
        assertNull(wsEndPoint.error);
    }
}
