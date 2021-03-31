//
// ========================================================================
// Copyright (c) Webtide LLC and others.
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

package org.eclipse.jetty.cdi.tests.websocket;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.inject.Inject;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.cdi.CdiDecoratingListener;
import org.eclipse.jetty.cdi.CdiServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.websocket.javax.client.JavaxWebSocketClientContainerProvider;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaxWebSocketCdiTest
{
    private Server _server;
    private WebSocketContainer _client;
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
        JavaxWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) ->
            wsContainer.addEndpoint(CdiEchoSocket.class));

        // Add to Server
        _server.setHandler(context);

        // Start Server
        _server.start();

        _client = JavaxWebSocketClientContainerProvider.getContainer(null);
    }

    @AfterEach
    public void after() throws Exception
    {
        JavaxWebSocketClientContainerProvider.stop(_client);
        _server.stop();
    }

    @ClientEndpoint
    public static class TestClientEndpoint
    {
        BlockingArrayQueue<String> _textMessages = new BlockingArrayQueue<>();
        CountDownLatch _closeLatch = new CountDownLatch(1);

        @OnMessage
        public void onMessage(String message)
        {
            _textMessages.add(message);
        }

        @OnClose
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
        Session session = _client.connectToServer(clientEndpoint, uri);
        session.getBasicRemote().sendText("hello world");
        assertThat(clientEndpoint._textMessages.poll(5, TimeUnit.SECONDS), is("hello world"));
        session.close();
        assertTrue(clientEndpoint._closeLatch.await(5, TimeUnit.SECONDS));
    }

    @ServerEndpoint("/echo")
    public static class CdiEchoSocket
    {
        @Inject
        public Logger logger;

        private Session session;

        @OnOpen
        public void onOpen(Session session)
        {
            logger.info("onOpen() session:" + session);
            this.session = session;
        }

        @OnMessage
        public void onMessage(String message) throws IOException
        {
            this.session.getBasicRemote().sendText(message);
        }

        @OnError
        public void onError(Throwable t)
        {
            t.printStackTrace();
        }

        @OnClose
        public void onClose(CloseReason close)
        {
            logger.info("onClose() close:" + close);
            this.session = null;
        }
    }
}
