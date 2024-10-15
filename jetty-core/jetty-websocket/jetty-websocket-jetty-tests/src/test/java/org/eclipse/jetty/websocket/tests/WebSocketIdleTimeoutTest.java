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
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.exceptions.WebSocketTimeoutException;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.websocket.api.StatusCode.UNDEFINED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketIdleTimeoutTest
{
    private static final int IDLE_TIMEOUT = 1000;
    private final AtomicBoolean _allowTimeout = new AtomicBoolean();
    private Server _server;
    private ServerConnector _connector;
    private WebSocketClient _client;
    private TimeoutEndpoint _serverEndpoint;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
        WebSocketUpgradeHandler upgradeHandler = WebSocketUpgradeHandler.from(_server);
        _serverEndpoint = new TimeoutEndpoint();
        upgradeHandler.getServerWebSocketContainer().addMapping("/", (req, resp, cb) -> _serverEndpoint);
        upgradeHandler.getServerWebSocketContainer().setIdleTimeout(Duration.ofMillis(IDLE_TIMEOUT));
        _server.setHandler(upgradeHandler);
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

    public class TimeoutEndpoint extends EventSocket
    {
        volatile CountDownLatch timeoutLatch;

        public void awaitTimeouts(int num) throws InterruptedException
        {
            timeoutLatch = new CountDownLatch(num);
            timeoutLatch.await();
        }

        @Override
        public void onOpen(Session session)
        {
            session.addIdleTimeoutListener(t ->
            {
                if (timeoutLatch != null)
                    timeoutLatch.countDown();
                return _allowTimeout.get();
            });
            super.onOpen(session);
        }
    }

    @Test
    public void testWebSocketIdleTimeout() throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        _client.connect(clientEndpoint, URI.create("ws://localhost:" + _connector.getLocalPort()));
        assertTrue(_serverEndpoint.openLatch.await(5, TimeUnit.SECONDS));

        // The WebSocket connection has not been closed but multiple timeout events have occurred.
        _serverEndpoint.awaitTimeouts(3);
        assertThat(_serverEndpoint.closeCode, equalTo(UNDEFINED));
        assertThat(_serverEndpoint.closeLatch.getCount(), equalTo(1L));

        // Allow the timeout listener to close the connection.
        _allowTimeout.set(true);
        assertTrue(_serverEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(_serverEndpoint.error, instanceOf(WebSocketTimeoutException.class));
    }
}
