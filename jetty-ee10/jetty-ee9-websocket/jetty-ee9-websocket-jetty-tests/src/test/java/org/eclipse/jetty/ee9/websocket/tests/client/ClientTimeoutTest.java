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

package org.eclipse.jetty.ee9.websocket.tests.client;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.exceptions.UpgradeException;
import org.eclipse.jetty.ee9.websocket.api.util.WSURI;
import org.eclipse.jetty.ee9.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.ee9.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee9.websocket.tests.EchoSocket;
import org.eclipse.jetty.ee9.websocket.tests.EventSocket;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ClientTimeoutTest
{
    private Server server;
    private WebSocketClient client;
    private final CountDownLatch createEndpoint = new CountDownLatch(1);

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);

        JettyWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
        {
            container.addMapping("/", (req, res) ->
            {
                try
                {
                    createEndpoint.await(5, TimeUnit.SECONDS);
                    return new EchoSocket();
                }
                catch (InterruptedException e)
                {
                    throw new IllegalStateException(e);
                }
            });
        });

        server.start();

        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        createEndpoint.countDown();
        client.stop();
        server.stop();
    }

    @Test
    public void testWebSocketClientTimeout() throws Exception
    {
        EventSocket clientSocket = new EventSocket();
        long timeout = 1000;
        client.setIdleTimeout(Duration.ofMillis(timeout));
        Future<Session> connect = client.connect(clientSocket, WSURI.toWebsocket(server.getURI()));

        ExecutionException executionException = assertThrows(ExecutionException.class, () -> connect.get(timeout * 2, TimeUnit.MILLISECONDS));
        assertThat(executionException.getCause(), instanceOf(UpgradeException.class));
        UpgradeException upgradeException = (UpgradeException)executionException.getCause();
        assertThat(upgradeException.getCause(), instanceOf(org.eclipse.jetty.websocket.core.exception.UpgradeException.class));
        org.eclipse.jetty.websocket.core.exception.UpgradeException coreUpgradeException =
            (org.eclipse.jetty.websocket.core.exception.UpgradeException)upgradeException.getCause();
        assertThat(coreUpgradeException.getCause(), instanceOf(TimeoutException.class));
    }

    @Test
    public void testClientUpgradeRequestTimeout() throws Exception
    {
        EventSocket clientSocket = new EventSocket();
        long timeout = 1000;
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setTimeout(timeout, TimeUnit.MILLISECONDS);
        Future<Session> connect = client.connect(clientSocket, WSURI.toWebsocket(server.getURI()), upgradeRequest);

        ExecutionException executionException = assertThrows(ExecutionException.class, () -> connect.get(timeout * 2, TimeUnit.MILLISECONDS));
        assertThat(executionException.getCause(), instanceOf(UpgradeException.class));
        UpgradeException upgradeException = (UpgradeException)executionException.getCause();
        assertThat(upgradeException.getCause(), instanceOf(org.eclipse.jetty.websocket.core.exception.UpgradeException.class));
        org.eclipse.jetty.websocket.core.exception.UpgradeException coreUpgradeException =
            (org.eclipse.jetty.websocket.core.exception.UpgradeException)upgradeException.getCause();
        assertThat(coreUpgradeException.getCause(), instanceOf(TimeoutException.class));
    }
}
