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

package org.eclipse.jetty.ee10.cdi.tests.websocket;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import jakarta.inject.Inject;
import org.eclipse.jetty.ee10.cdi.CdiDecoratingListener;
import org.eclipse.jetty.ee10.cdi.CdiServletContainerInitializer;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee10.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JettyWebSocketCdiTest
{
    private Server _server;
    private WebSocketClient _client;
    private ServerConnector _connector;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        // Enable Weld + CDI
        context.setInitParameter(CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, CdiDecoratingListener.MODE);
        context.addServletContainerInitializer(new CdiServletContainerInitializer());
        context.addServletContainerInitializer(new org.jboss.weld.environment.servlet.EnhancedListener());

        // Add WebSocket endpoints
        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) ->
            wsContainer.addMapping("/echo", CdiEchoSocket.class));

        // Add to Server
        _server.setHandler(context);

        // Start Server
        _server.start();

        _client = new WebSocketClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @WebSocket
    public static class TestClientEndpoint
    {
        BlockingArrayQueue<String> _textMessages = new BlockingArrayQueue<>();
        CountDownLatch _closeLatch = new CountDownLatch(1);

        @OnWebSocketMessage
        public void onMessage(String message)
        {
            _textMessages.add(message);
        }

        @OnWebSocketClose
        public void onClose()
        {
            _closeLatch.countDown();
        }
    }

    @Test
    public void testBasicEcho() throws Exception
    {
        // If we can get an echo from the websocket endpoint we know that CDI injection of the logger worked as there was no NPE.
        TestClientEndpoint clientEndpoint = new TestClientEndpoint();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/echo");
        Session session = _client.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);
        session.getRemote().sendString("hello world");
        assertThat(clientEndpoint._textMessages.poll(5, TimeUnit.SECONDS), is("hello world"));
        session.close();
        assertTrue(clientEndpoint._closeLatch.await(5, TimeUnit.SECONDS));
    }

    @WebSocket()
    public static class CdiEchoSocket
    {
        @Inject
        public Logger logger;

        private Session session;

        @OnWebSocketConnect
        public void onOpen(Session session)
        {
            logger.info("onOpen() session:" + session);
            this.session = session;
        }

        @OnWebSocketMessage
        public void onMessage(String message) throws IOException
        {
            this.session.getRemote().sendString(message);
        }

        @OnWebSocketError
        public void onError(Throwable t)
        {
            t.printStackTrace();
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            logger.info("onClose() close: " + statusCode + ":" + reason);
            this.session = null;
        }
    }
}
