//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketSessionListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConcurrentConnectTest
{
    private static final int MAX_CONNECTIONS = 150;

    private Server server;
    private WebSocketClient client;
    private URI uri;

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context);
        context.setHandler(wsHandler);
        wsHandler.configure(container ->
            container.addMapping("/", (rq, rs, cb) -> new EchoSocket()));

        server.setHandler(context);
        server.start();
        uri = new URI("ws://localhost:" + connector.getLocalPort());

        client = new WebSocketClient();
        client.getHttpClient().setMaxConnectionsPerDestination(MAX_CONNECTIONS);
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testConcurrentConnect() throws Exception
    {
        List<EventSocket> listeners = new ArrayList<>();
        CloseListener closeListener = new CloseListener();
        client.addSessionListener(closeListener);

        for (int i = 0; i < MAX_CONNECTIONS; i++)
        {
            try
            {
                EventSocket wsListener = new EventSocket();
                listeners.add(wsListener);
                client.connect(wsListener, uri);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        for (EventSocket l : listeners)
        {
            assertTrue(l.openLatch.await(5, TimeUnit.SECONDS));
        }

        for (EventSocket l : listeners)
        {
            l.session.sendText("ping", Callback.NOOP);
            assertThat(l.textMessages.poll(5, TimeUnit.SECONDS), is("ping"));
            l.session.close(StatusCode.NORMAL, "close from client", Callback.NOOP);
        }

        for (EventSocket l : listeners)
        {
            assertTrue(l.closeLatch.await(5, TimeUnit.SECONDS));
            assertThat(l.closeCode, is(StatusCode.NORMAL));
            assertThat(l.closeReason, is("close from client"));
            assertNull(l.error);
        }

        assertTrue(closeListener.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(client.getOpenSessions().isEmpty());
        assertTrue(client.getContainedBeans(WebSocketSession.class).isEmpty());
    }

    public static class CloseListener implements WebSocketSessionListener
    {
        public CountDownLatch closeLatch = new CountDownLatch(MAX_CONNECTIONS);

        @Override
        public void onWebSocketSessionClosed(Session session)
        {
            closeLatch.countDown();
        }
    }
}
