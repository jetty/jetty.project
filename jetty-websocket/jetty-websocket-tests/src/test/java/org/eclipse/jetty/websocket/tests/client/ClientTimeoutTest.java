//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.tests.client;

import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.eclipse.jetty.websocket.tests.EchoSocket;
import org.eclipse.jetty.websocket.tests.EventSocket;
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

        NativeWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
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
        contextHandler.addFilter(WebSocketUpgradeFilter.class, "/", EnumSet.of(DispatcherType.REQUEST));
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
        client.setMaxIdleTimeout(timeout);
        Future<Session> connect = client.connect(clientSocket, WSURI.toWebsocket(server.getURI()));

        ExecutionException executionException = assertThrows(ExecutionException.class, () -> connect.get(timeout * 2, TimeUnit.MILLISECONDS));
        assertThat(executionException.getCause(), instanceOf(UpgradeException.class));
        UpgradeException upgradeException = (UpgradeException)executionException.getCause();
        assertThat(upgradeException.getCause(), instanceOf(TimeoutException.class));
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
        assertThat(upgradeException.getCause(), instanceOf(TimeoutException.class));
    }
}
