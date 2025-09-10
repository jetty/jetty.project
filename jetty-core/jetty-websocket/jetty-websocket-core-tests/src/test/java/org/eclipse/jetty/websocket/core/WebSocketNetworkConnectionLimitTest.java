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

import java.io.EOFException;
import java.net.URI;
import java.nio.channels.AsynchronousCloseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.server.NetworkConnectionLimit;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.VirtualConnectionLimit;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

public class WebSocketNetworkConnectionLimitTest
{
    private Server _server;
    private WebSocketUpgradeHandler _upgradeHandler;
    private WebSocketCoreClient _client;
    private ServerConnector _serverConnector;

    public void startServer() throws Exception
    {
        _server.start();
        _client = new WebSocketCoreClient();
        _client.start();
    }

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _serverConnector = new ServerConnector(_server);
        _server.addConnector(_serverConnector);
        _upgradeHandler = new WebSocketUpgradeHandler();
        _server.setHandler(_upgradeHandler);
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testNetworkConnectionLimit() throws Exception
    {
        int connectionLimit = 5;
        NetworkConnectionLimit limiter = new NetworkConnectionLimit(connectionLimit, _serverConnector);
        _serverConnector.addBean(limiter);
        startServer();

        _upgradeHandler.addMapping("/", (req, resp, cb) -> new EchoFrameHandler());
        URI uri = URI.create("ws://localhost:" + _serverConnector.getLocalPort());

        List<TestMessageHandler> clientHandlers = new ArrayList<>();
        for (int i = 0; i < connectionLimit; i++)
        {
            TestMessageHandler clientHandler = new TestMessageHandler();
            clientHandlers.add(clientHandler);
            _client.connect(clientHandler, uri).get(5, TimeUnit.SECONDS);
            assertTrue(clientHandler.openLatch.await(5, TimeUnit.SECONDS));
            awaitConnections(i + 1, limiter);
        }

        // Trying to open an additional connection results in a failure.
        assertFalse(_serverConnector.isAccepting());
        TestMessageHandler clientHandler = new TestMessageHandler();
        _client.getHttpClient().setConnectTimeout(1000);
        _client.getHttpClient().setIdleTimeout(1000);
        ExecutionException error = assertThrows(ExecutionException.class, () -> _client.connect(clientHandler, uri).get(5, TimeUnit.SECONDS));
        assertValidCause(error);
        assertThat(limiter.getNetworkConnectionCount(), equalTo(connectionLimit));

        // Close all the sessions.
        for (TestMessageHandler handler : clientHandlers)
        {
            handler.getCoreSession().close(Callback.NOOP);
            assertTrue(handler.closeLatch.await(5, TimeUnit.SECONDS));
            assertThat(handler.closeStatus.getCode(), equalTo(CloseStatus.NO_CODE));
        }

        // All connections should be closed.
        awaitConnections(0, limiter);

        // Now additional connections can be opened without error.
        TestMessageHandler clientHandler2 = new TestMessageHandler();
        _client.connect(clientHandler2, uri).get(5, TimeUnit.SECONDS);
        assertTrue(clientHandler2.openLatch.await(5, TimeUnit.SECONDS));
        awaitConnections(1, limiter);
        clientHandler2.getCoreSession().close(Callback.NOOP);
        assertTrue(clientHandler2.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientHandler2.closeStatus.getCode(), equalTo(CloseStatus.NO_CODE));
        awaitConnections(0, limiter);
    }

    @Test
    public void testVirtualConnectionLimit() throws Exception
    {
        int connectionLimit = 1;
        VirtualConnectionLimit limiter = new VirtualConnectionLimit(connectionLimit, _serverConnector);
        _serverConnector.addBean(limiter);
        startServer();

        _upgradeHandler.addMapping("/", (req, resp, cb) -> new EchoFrameHandler());
        URI uri = URI.create("ws://localhost:" + _serverConnector.getLocalPort());

        List<TestMessageHandler> clientHandlers = new ArrayList<>();
        for (int i = 0; i < connectionLimit; i++)
        {
            TestMessageHandler clientHandler = new TestMessageHandler();
            clientHandlers.add(clientHandler);
            _client.connect(clientHandler, uri).get(5, TimeUnit.SECONDS);
            assertTrue(clientHandler.openLatch.await(5, TimeUnit.SECONDS));
            awaitConnections(i + 1, limiter);
        }

        // Trying to open an additional connection results in a failure.
        assertFalse(_serverConnector.isAccepting());
        TestMessageHandler clientHandler = new TestMessageHandler();
        _client.getHttpClient().setConnectTimeout(1000);
        _client.getHttpClient().setIdleTimeout(1000);
        ExecutionException error = assertThrows(ExecutionException.class, () -> _client.connect(clientHandler, uri).get(5, TimeUnit.SECONDS));
        assertValidCause(error);
        assertThat(limiter.getVirtualConnectionCount(), equalTo(connectionLimit));

        // Close all the sessions.
        for (TestMessageHandler handler : clientHandlers)
        {
            handler.getCoreSession().close(Callback.NOOP);
            assertTrue(handler.closeLatch.await(5, TimeUnit.SECONDS));
            assertThat(handler.closeStatus.getCode(), equalTo(CloseStatus.NO_CODE));
        }

        // All connections should be closed.
        awaitConnections(0, limiter);

        // Now additional connections can be opened without error.
        TestMessageHandler clientHandler2 = new TestMessageHandler();
        _client.connect(clientHandler2, uri).get(5, TimeUnit.SECONDS);
        assertTrue(clientHandler2.openLatch.await(5, TimeUnit.SECONDS));
        awaitConnections(1, limiter);
        clientHandler2.getCoreSession().close(Callback.NOOP);
        assertTrue(clientHandler2.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientHandler2.closeStatus.getCode(), equalTo(CloseStatus.NO_CODE));
        awaitConnections(0, limiter);
    }

    public void assertValidCause(Throwable error)
    {
        Throwable cause = error.getCause();
        if (cause instanceof AsynchronousCloseException)
            return;
        if (cause instanceof EOFException)
            return;

        while (cause != null)
        {
            if (cause instanceof TimeoutException)
                return;
            cause = cause.getCause();
        }

        fail("No timeout exception cause", error);
    }

    public void awaitConnections(int connections, NetworkConnectionLimit limiter)
    {
        await()
            .atMost(1, TimeUnit.SECONDS)
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() ->
            {
                assertThat(limiter.getNetworkConnectionCount(), equalTo(connections));
                assertThat(limiter.getPendingNetworkConnectionCount(), equalTo(0));
            });
    }

    public void awaitConnections(int connections, VirtualConnectionLimit limiter)
    {
        await()
            .atMost(1, TimeUnit.SECONDS)
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() ->
            {
                assertThat(limiter.getVirtualConnectionCount(), equalTo(connections));
                assertThat(limiter.getPendingVirtualConnectionCount(), equalTo(0));
            });
    }
}
