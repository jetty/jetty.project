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

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.exceptions.WebSocketTimeoutException;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.websocket.api.StatusCode.NORMAL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketIdleTimeoutTest
{
    private static final long IDLE_TIMEOUT_CONNECTOR = 10000;
    private static final long IDLE_TIMEOUT_HTTP_CONFIG = 10001;
    private static final long IDLE_TIMEOUT_CONTAINER = 10002;
    private static final long IDLE_TIMEOUT_SESSION = 10003;

    private final TimeoutEndpoint _serverEndpoint = new TimeoutEndpoint();
    private Server _server;
    private ServerConnector _connector;
    private WebSocketClient _client;
    private WebSocketUpgradeHandler _upgradeHandler;
    private HttpConfiguration _httpConfig;

    public void before(Runnable runnable) throws Exception
    {
        _server = new Server();
        _httpConfig = new HttpConfiguration();
        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(_httpConfig);
        _connector = new ServerConnector(_server, httpConnectionFactory);
        _server.addConnector(_connector);
        _upgradeHandler = WebSocketUpgradeHandler.from(_server);
        _server.setHandler(_upgradeHandler);
        runnable.run();
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

    public static class TimeoutEndpoint extends CloseTrackingEndpoint
    {
        private long idleTimeout = -1;

        public void setIdleTimeout(long idleTimeout)
        {
            this.idleTimeout = idleTimeout;
        }

        @Override
        public void onWebSocketOpen(Session session)
        {
            // Add an idle timeout listener to the session, which decides whether to close connection based on `_allowTimeout`.
            if (idleTimeout >= 0)
                session.setIdleTimeout(Duration.ofMillis(idleTimeout));
            super.onWebSocketOpen(session);
        }

        @Override
        public void onWebSocketText(String message)
        {
            if ("getIdleTimeout".equals(message))
            {
                // Use the endpoint directly to get the true idleTimeout value.
                WebSocketSession webSocketSession = (WebSocketSession)getSession();
                WebSocketCoreSession coreSession = (WebSocketCoreSession)webSocketSession.getCoreSession();
                long idleTimeout = coreSession.getConnection().getEndPoint().getIdleTimeout();
                getSession().sendText(Long.toString(idleTimeout), Callback.NOOP);
            }
        }
    }

    @Test
    public void testWebSocketIdleTimeout() throws Exception
    {
        before(() ->
        {
            ServerWebSocketContainer container = _upgradeHandler.getServerWebSocketContainer();
            container.addMapping("/", (req, resp, cb) -> _serverEndpoint);
            container.setIdleTimeout(Duration.ofMillis(1000));
        });

        EventSocket clientEndpoint = new EventSocket();
        _client.connect(clientEndpoint, URI.create("ws://localhost:" + _connector.getLocalPort()));
        assertTrue(_serverEndpoint.openLatch.await(5, TimeUnit.SECONDS));

        // Allow the timeout listener to close the connection.
        assertTrue(_serverEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(_serverEndpoint.error.get(), instanceOf(WebSocketTimeoutException.class));
    }

    @Test
    public void testConnectorIdleTimeout() throws Exception
    {
        before(() ->
        {
            ServerWebSocketContainer container = _upgradeHandler.getServerWebSocketContainer();
            container.addMapping("/", (req, resp, cb) -> _serverEndpoint);

            _connector.setIdleTimeout(IDLE_TIMEOUT_CONNECTOR);
        });

        assertIdleTimeoutAndClose(IDLE_TIMEOUT_CONNECTOR);
    }

    @Test
    public void testHttpConfigurationIdleTimeout() throws Exception
    {
        before(() ->
        {
            ServerWebSocketContainer container = _upgradeHandler.getServerWebSocketContainer();
            container.addMapping("/", (req, resp, cb) -> _serverEndpoint);

            _connector.setIdleTimeout(IDLE_TIMEOUT_CONNECTOR);
            _httpConfig.setIdleTimeout(IDLE_TIMEOUT_HTTP_CONFIG);
        });

        assertIdleTimeoutAndClose(IDLE_TIMEOUT_CONNECTOR);
    }

    @Test
    public void testWebSocketContainerIdleTimeout() throws Exception
    {
        before(() ->
        {
            ServerWebSocketContainer container = _upgradeHandler.getServerWebSocketContainer();
            container.addMapping("/", (req, resp, cb) -> _serverEndpoint);

            _connector.setIdleTimeout(IDLE_TIMEOUT_CONNECTOR);
            _httpConfig.setIdleTimeout(IDLE_TIMEOUT_HTTP_CONFIG);
            container.setIdleTimeout(Duration.ofMillis(IDLE_TIMEOUT_CONTAINER));
        });

        assertIdleTimeoutAndClose(IDLE_TIMEOUT_CONTAINER);
    }

    @Test
    public void testWebSocketSessionIdleTimeout() throws Exception
    {
        before(() ->
        {
            ServerWebSocketContainer container = _upgradeHandler.getServerWebSocketContainer();
            _connector.setIdleTimeout(IDLE_TIMEOUT_CONNECTOR);
            _httpConfig.setIdleTimeout(IDLE_TIMEOUT_HTTP_CONFIG);
            container.setIdleTimeout(Duration.ofMillis(IDLE_TIMEOUT_CONTAINER));
            _serverEndpoint.setIdleTimeout(IDLE_TIMEOUT_SESSION);

            container.addMapping("/", (req, resp, cb) -> _serverEndpoint);
        });

        assertIdleTimeoutAndClose(IDLE_TIMEOUT_SESSION);
    }

    private void assertIdleTimeoutAndClose(long expectedIdleTimeout) throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        _client.connect(clientEndpoint, URI.create("ws://localhost:" + _connector.getLocalPort()));
        assertTrue(_serverEndpoint.openLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientEndpoint.openLatch.await(5, TimeUnit.SECONDS));

        clientEndpoint.session.sendText("getIdleTimeout", Callback.NOOP);
        String idleTimeout = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(idleTimeout, equalTo(Long.toString(expectedIdleTimeout)));

        _serverEndpoint.getSession().close();
        assertTrue(_serverEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(_serverEndpoint.closeCode, equalTo(NORMAL));
    }
}
