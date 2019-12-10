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

package org.eclipse.jetty.websocket.javax.tests.server;

import java.net.URI;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.websocket.ContainerProvider;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.websocket.javax.common.decoders.StringDecoder;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.javax.tests.EventSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Example of an annotated echo server discovered via annotation scanning.
 */
public class ServerDecoderTest
{
    static BlockingArrayQueue<EventSocket> serverSockets = new BlockingArrayQueue<>();

    private Server server;
    private URI serverURI;

    public static class EqualsAppendDecoder extends StringDecoder
    {
        @Override
        public String decode(String s)
        {
            return s + "=";
        }
    }

    public static class PlusAppendDecoder extends StringDecoder
    {
        @Override
        public String decode(String s)
        {
            return s + "+";
        }
    }

    @ServerEndpoint(value = "/annotated", decoders = {EqualsAppendDecoder.class})
    public static class ConfiguredEchoSocket extends EventSocket
    {
        @Override
        public void onOpen(Session session)
        {
            serverSockets.add(this);
            super.onOpen(session);
        }
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();
        ServerConnector serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);
        ServletContextHandler servletContextHandler = new ServletContextHandler(null, "/");
        server.setHandler(servletContextHandler);

        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(ConfiguredEchoSocket.class, "/configured")
            .decoders(Collections.singletonList(PlusAppendDecoder.class))
            .build();

        JavaxWebSocketServletContainerInitializer.configure(servletContextHandler, ((servletContext, serverContainer) ->
        {
            serverContainer.addEndpoint(ConfiguredEchoSocket.class);
            serverContainer.addEndpoint(config);
        }));

        server.start();
        serverURI = new URI("ws://localhost:" + serverConnector.getLocalPort());
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testAnnotatedDecoder() throws Exception
    {
        WebSocketContainer client = ContainerProvider.getWebSocketContainer();
        EventSocket clientSocket = new EventSocket();
        Session session = client.connectToServer(clientSocket, serverURI.resolve("/annotated"));
        session.getBasicRemote().sendText("hello world");

        EventSocket serverSocket = serverSockets.poll(5, TimeUnit.SECONDS);
        assertNotNull(serverSocket);
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));
        String msg = serverSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat(msg, is("hello world="));

        clientSocket.session.close();
        clientSocket.closeLatch.await(5, TimeUnit.SECONDS);
        serverSocket.closeLatch.await(5, TimeUnit.SECONDS);
    }

    @Test
    public void testConfiguredDecoder() throws Exception
    {
        WebSocketContainer client = ContainerProvider.getWebSocketContainer();
        EventSocket clientSocket = new EventSocket();
        Session session = client.connectToServer(clientSocket, serverURI.resolve("/configured"));
        session.getBasicRemote().sendText("hello world");

        EventSocket serverSocket = serverSockets.poll(5, TimeUnit.SECONDS);
        assertNotNull(serverSocket);
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));
        String msg = serverSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat(msg, is("hello world+"));

        clientSocket.session.close();
        clientSocket.closeLatch.await(5, TimeUnit.SECONDS);
        serverSocket.closeLatch.await(5, TimeUnit.SECONDS);
    }
}
