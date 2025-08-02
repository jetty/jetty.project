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

package org.eclipse.jetty.websocket.core;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.server.NetworkConnectionLimit;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

public class WebSocketNetworkConnectionLimitTest
{
    private static final int CONNECTION_LIMIT = 5;

    private Server _server;
    private WebSocketUpgradeHandler _upgradeHandler;
    private WebSocketCoreClient _client;
    private ServerConnector _serverConnector;
    private NetworkConnectionLimit _networkConnectionLimit;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _serverConnector = new ServerConnector(_server);
        _server.addConnector(_serverConnector);
        _upgradeHandler = new WebSocketUpgradeHandler();
        _server.setHandler(_upgradeHandler);

        _networkConnectionLimit = new NetworkConnectionLimit(CONNECTION_LIMIT, _serverConnector);
        _serverConnector.addBean(_networkConnectionLimit);

        _server.start();

        _client = new WebSocketCoreClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void test() throws Exception
    {
        _upgradeHandler.addMapping("/", (req, resp, cb) -> new EchoFrameHandler());
        URI uri = URI.create("ws://localhost:" + _serverConnector.getLocalPort());

        List<TestMessageHandler> clientHandlers = new ArrayList<>();
        for (int i = 0; i < CONNECTION_LIMIT; i++)
        {
            TestMessageHandler clientHandler = new TestMessageHandler();
            clientHandlers.add(clientHandler);
            _client.connect(clientHandler, uri).get(5, TimeUnit.SECONDS);
            assertTrue(clientHandler.openLatch.await(5, TimeUnit.SECONDS));
            awaitConnections(i + 1);
        }

        // Trying to open an additional connection results in a failure.
        TestMessageHandler clientHandler = new TestMessageHandler();
        _client.getHttpClient().setConnectTimeout(1000);
        _client.getHttpClient().setIdleTimeout(1000);
        ExecutionException error = assertThrows(ExecutionException.class, () -> _client.connect(clientHandler, uri).get(5, TimeUnit.SECONDS));
        assertCausedByTimeout(error);
        assertThat(_networkConnectionLimit.getNetworkConnectionCount(), equalTo(CONNECTION_LIMIT));

        // Close all the sessions.
        for (TestMessageHandler handler : clientHandlers)
        {
            handler.getCoreSession().close(Callback.NOOP);
            assertTrue(handler.closeLatch.await(5, TimeUnit.SECONDS));
            assertThat(handler.closeStatus.getCode(), equalTo(CloseStatus.NO_CODE));
        }

        // All connections should be closed.
        awaitConnections(0);

        // Now additional connections can be opened without error.
        TestMessageHandler clientHandler2 = new TestMessageHandler();
        _client.connect(clientHandler2, uri).get(5, TimeUnit.SECONDS);
        assertTrue(clientHandler2.openLatch.await(5, TimeUnit.SECONDS));
        awaitConnections(1);
        clientHandler2.getCoreSession().close(Callback.NOOP);
        assertTrue(clientHandler2.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientHandler2.closeStatus.getCode(), equalTo(CloseStatus.NO_CODE));
        awaitConnections(0);
    }

    public void assertCausedByTimeout(Throwable error)
    {
        Throwable cause = error.getCause();
        while (cause != null)
        {
            if (cause instanceof TimeoutException)
                return;
            cause = cause.getCause();
        }

        fail("No timeout exception cause", error);
    }

    public void awaitConnections(int connections)
    {
        await()
            .atMost(1, TimeUnit.SECONDS)
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() ->
            {
                assertThat(_networkConnectionLimit.getNetworkConnectionCount(), equalTo(connections));
                assertThat(_networkConnectionLimit.getPendingNetworkConnectionCount(), equalTo(0));
            });
    }
}
