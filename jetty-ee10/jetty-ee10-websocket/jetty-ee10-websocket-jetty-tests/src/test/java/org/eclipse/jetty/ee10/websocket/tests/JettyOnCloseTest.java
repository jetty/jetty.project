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

package org.eclipse.jetty.ee10.websocket.tests;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.api.StatusCode;
import org.eclipse.jetty.ee10.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JettyOnCloseTest
{
    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;
    private OnCloseEndpoint serverEndpoint = new OnCloseEndpoint();

    @WebSocket
    public static class OnCloseEndpoint extends EventSocket
    {
        private Consumer<Session> onClose;

        public void setOnClose(Consumer<Session> onClose)
        {
            this.onClose = onClose;
        }

        @Override
        public void onClose(int statusCode, String reason)
        {
            onClose.accept(session);
            super.onClose(statusCode, reason);
        }
    }

    @WebSocket
    public static class BlockingClientEndpoint extends EventSocket
    {
        private CountDownLatch blockInClose = new CountDownLatch(1);

        public void unBlockClose()
        {
            blockInClose.countDown();
        }

        @Override
        public void onClose(int statusCode, String reason)
        {
            try
            {
                blockInClose.await();
                super.onClose(statusCode, reason);
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);

        JettyWebSocketServletContainerInitializer.configure(contextHandler, ((servletContext, container) ->
            container.addMapping("/", (req, resp) -> serverEndpoint)));

        client = new WebSocketClient();
        server.start();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void changeStatusCodeInOnClose() throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/");
        client.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);

        assertTrue(serverEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        serverEndpoint.setOnClose((session) -> session.close(StatusCode.SERVICE_RESTART, "custom close reason"));

        clientEndpoint.session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.SERVICE_RESTART));
        assertThat(clientEndpoint.closeReason, is("custom close reason"));
    }

    @Test
    public void secondCloseFromOnCloseFails() throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/");
        client.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);

        assertTrue(serverEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        serverEndpoint.setOnClose(Session::close);

        serverEndpoint.session.close(StatusCode.NORMAL, "first close");
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.NORMAL));
        assertThat(clientEndpoint.closeReason, is("first close"));
    }

    @Test
    public void abnormalStatusDoesNotChange() throws Exception
    {
        BlockingClientEndpoint clientEndpoint = new BlockingClientEndpoint();
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/");
        client.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);

        assertTrue(serverEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        serverEndpoint.setOnClose((session) ->
        {
            session.close(StatusCode.SERVER_ERROR, "abnormal close 2");
            clientEndpoint.unBlockClose();
        });

        serverEndpoint.session.close(StatusCode.PROTOCOL, "abnormal close 1");
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.PROTOCOL));
        assertThat(clientEndpoint.closeReason, is("abnormal close 1"));
    }

    @Test
    public void onErrorOccurringAfterOnClose() throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/");
        client.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);

        assertTrue(serverEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        serverEndpoint.setOnClose((session) ->
        {
            throw new RuntimeException("trigger onError from onClose");
        });

        clientEndpoint.session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.SERVER_ERROR));
        assertThat(clientEndpoint.closeReason, containsString("trigger onError from onClose"));

        assertTrue(serverEndpoint.errorLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverEndpoint.error, instanceOf(RuntimeException.class));
        assertThat(serverEndpoint.error.getMessage(), containsString("trigger onError from onClose"));
    }
}
