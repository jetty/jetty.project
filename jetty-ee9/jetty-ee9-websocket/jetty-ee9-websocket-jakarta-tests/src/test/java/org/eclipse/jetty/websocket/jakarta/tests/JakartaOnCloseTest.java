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

package org.eclipse.jetty.websocket.jakarta.tests;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JakartaOnCloseTest
{
    private static final BlockingArrayQueue<OnCloseEndpoint> serverEndpoints = new BlockingArrayQueue<>();

    private Server server;
    private ServerConnector connector;
    private final JakartaWebSocketClientContainer client = new JakartaWebSocketClientContainer();

    @ServerEndpoint("/")
    public static class OnCloseEndpoint extends EventSocket
    {
        private Consumer<Session> onClose;

        public void setOnClose(Consumer<Session> onClose)
        {
            this.onClose = onClose;
        }

        @Override
        public void onOpen(Session session, EndpointConfig endpointConfig)
        {
            super.onOpen(session, endpointConfig);
            serverEndpoints.add(this);
        }

        @Override
        public void onClose(CloseReason reason)
        {
            super.onClose(reason);
            onClose.accept(session);
        }
    }

    @ClientEndpoint
    public static class BlockingClientEndpoint extends EventSocket
    {
        private final CountDownLatch blockInClose = new CountDownLatch(1);

        public void unBlockClose()
        {
            blockInClose.countDown();
        }

        @Override
        public void onClose(CloseReason reason)
        {
            try
            {
                blockInClose.await();
                super.onClose(reason);
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

        JakartaWebSocketServletContainerInitializer.configure(contextHandler, ((servletContext, container) ->
            container.addEndpoint(OnCloseEndpoint.class)));

        client.start();
        server.start();
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
        client.connectToServer(clientEndpoint, uri);

        OnCloseEndpoint serverEndpoint = Objects.requireNonNull(serverEndpoints.poll(5, TimeUnit.SECONDS));
        serverEndpoint.setOnClose((session) -> assertDoesNotThrow(() ->
                session.close(new CloseReason(CloseCodes.SERVICE_RESTART, "custom close reason"))));
        assertTrue(serverEndpoint.openLatch.await(5, TimeUnit.SECONDS));

        clientEndpoint.session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), is(CloseCodes.SERVICE_RESTART));
        assertThat(clientEndpoint.closeReason.getReasonPhrase(), is("custom close reason"));
    }

    @Test
    public void secondCloseFromOnCloseFails() throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/");
        client.connectToServer(clientEndpoint, uri);

        OnCloseEndpoint serverEndpoint = Objects.requireNonNull(serverEndpoints.poll(5, TimeUnit.SECONDS));
        assertTrue(serverEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        serverEndpoint.setOnClose((session) -> assertDoesNotThrow((Executable)session::close));

        serverEndpoint.session.close(new CloseReason(CloseCodes.NORMAL_CLOSURE, "first close"));
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), is(CloseCodes.NORMAL_CLOSURE));
        assertThat(clientEndpoint.closeReason.getReasonPhrase(), is("first close"));
    }

    @Test
    public void abnormalStatusDoesNotChange() throws Exception
    {
        BlockingClientEndpoint clientEndpoint = new BlockingClientEndpoint();
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/");
        client.connectToServer(clientEndpoint, uri);

        OnCloseEndpoint serverEndpoint = Objects.requireNonNull(serverEndpoints.poll(5, TimeUnit.SECONDS));
        assertTrue(serverEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        serverEndpoint.setOnClose((session) ->
        {
            assertDoesNotThrow(() -> session.close(new CloseReason(CloseCodes.UNEXPECTED_CONDITION, "abnormal close 2")));
            clientEndpoint.unBlockClose();
        });

        serverEndpoint.session.close(new CloseReason(CloseCodes.PROTOCOL_ERROR, "abnormal close 1"));
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), is(CloseCodes.PROTOCOL_ERROR));
        assertThat(clientEndpoint.closeReason.getReasonPhrase(), is("abnormal close 1"));
    }

    @ClientEndpoint
    public class ThrowOnCloseSocket extends EventSocket
    {
        @Override
        public void onClose(CloseReason reason)
        {
            super.onClose(reason);
            throw new RuntimeException("trigger onError from client onClose");
        }
    }

    @Test
    public void onErrorOccurringAfterOnClose() throws Exception
    {
        EventSocket clientEndpoint = new ThrowOnCloseSocket();
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/");
        client.connectToServer(clientEndpoint, uri);

        OnCloseEndpoint serverEndpoint = Objects.requireNonNull(serverEndpoints.poll(5, TimeUnit.SECONDS));
        assertTrue(serverEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        serverEndpoint.setOnClose((session) ->
        {
            throw new RuntimeException("trigger onError from server onClose");
        });

        // Initiate close on client to cause the server to throw in onClose.
        clientEndpoint.session.close();

        // Test the receives the normal close, and throws in onClose.
        assertTrue(serverEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverEndpoint.closeReason.getCloseCode(), is(CloseCodes.NORMAL_CLOSURE));
        assertTrue(serverEndpoint.errorLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverEndpoint.error, instanceOf(RuntimeException.class));
        assertThat(serverEndpoint.error.getMessage(), containsString("trigger onError from server onClose"));

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), is(CloseCodes.UNEXPECTED_CONDITION));
        assertThat(clientEndpoint.closeReason.getReasonPhrase(), containsString("trigger onError from server onClose"));
        assertTrue(clientEndpoint.errorLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.error, instanceOf(RuntimeException.class));
        assertThat(clientEndpoint.error.getMessage(), containsString("trigger onError from client onClose"));
    }
}
