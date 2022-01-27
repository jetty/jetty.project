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

package org.eclipse.jetty.websocket.javax.tests.server;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.websocket.javax.tests.LocalServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JsrBatchModeTest
{
    public static class BasicEchoEndpoint extends Endpoint implements MessageHandler.Whole<String>
    {
        private javax.websocket.Session session;

        @Override
        public void onMessage(String msg)
        {
            // reply with echo
            session.getAsyncRemote().sendText(msg);
        }

        @Override
        public void onOpen(javax.websocket.Session session, EndpointConfig config)
        {
            this.session = session;
            this.session.addMessageHandler(this);
        }
    }

    private LocalServer server;
    private WebSocketContainer client;

    @BeforeEach
    public void prepare() throws Exception
    {
        server = new LocalServer();
        server.start();

        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(BasicEchoEndpoint.class, "/").build();
        server.getServerContainer().addEndpoint(config);

        client = ContainerProvider.getWebSocketContainer();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        server.stop();
    }

    @Test
    public void testBatchModeOn() throws Exception
    {
        ClientEndpointConfig config = ClientEndpointConfig.Builder.create().build();

        URI uri = server.getWsUri();

        CountDownLatch latch = new CountDownLatch(1);
        EndpointAdapter endpoint = new EndpointAdapter(latch);

        try (Session session = client.connectToServer(endpoint, config, uri))
        {
            RemoteEndpoint.Async remote = session.getAsyncRemote();
            remote.setBatchingAllowed(true);

            Future<Void> future = remote.sendText("batch_mode_on");
            // The write is aggregated and therefore completes immediately.
            future.get(1, TimeUnit.MICROSECONDS);

            // Did not flush explicitly, so the message should not be back yet.
            assertFalse(latch.await(1, TimeUnit.SECONDS));

            // Explicitly flush.
            remote.flushBatch();

            // Wait for the echo.
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testBatchModeOff() throws Exception
    {
        ClientEndpointConfig config = ClientEndpointConfig.Builder.create().build();

        URI uri = server.getWsUri();

        CountDownLatch latch = new CountDownLatch(1);
        EndpointAdapter endpoint = new EndpointAdapter(latch);

        try (Session session = client.connectToServer(endpoint, config, uri))
        {
            RemoteEndpoint.Async remote = session.getAsyncRemote();
            remote.setBatchingAllowed(false);

            Future<Void> future = remote.sendText("batch_mode_off");
            // The write is immediate.
            future.get(1, TimeUnit.SECONDS);

            // Wait for the echo.
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testBatchModeAuto() throws Exception
    {
        ClientEndpointConfig config = ClientEndpointConfig.Builder.create().build();

        URI uri = server.getWsUri();

        CountDownLatch latch = new CountDownLatch(1);
        EndpointAdapter endpoint = new EndpointAdapter(latch);

        try (Session session = client.connectToServer(endpoint, config, uri))
        {
            RemoteEndpoint.Async remote = session.getAsyncRemote();

            Future<Void> future = remote.sendText("batch_mode_auto");
            // The write is immediate, as per the specification.
            future.get(1, TimeUnit.SECONDS);

            // Wait for the echo.
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    public static class EndpointAdapter extends Endpoint implements MessageHandler.Whole<String>
    {
        private final CountDownLatch latch;

        public EndpointAdapter(CountDownLatch latch)
        {
            this.latch = latch;
        }

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(this);
        }

        @Override
        public void onMessage(String message)
        {
            latch.countDown();
        }
    }
}
