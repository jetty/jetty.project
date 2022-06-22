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

package org.eclipse.jetty.ee9.websocket.tests.server;

import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.concurrent.Future;

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.StatusCode;
import org.eclipse.jetty.ee9.websocket.api.util.WSURI;
import org.eclipse.jetty.ee9.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.ee9.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee9.websocket.common.WebSocketSession;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee9.websocket.tests.CloseTrackingEndpoint;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests various close scenarios
 */
public class ServerCloseTest
{
    private WebSocketClient client;
    private Server server;
    private ServerCloseCreator serverEndpointCreator;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        ServletHolder closeEndpoint = new ServletHolder(new JettyWebSocketServlet()
        {
            @Override
            public void configure(JettyWebSocketServletFactory factory)
            {
                factory.setIdleTimeout(Duration.ofSeconds(2));
                serverEndpointCreator = new ServerCloseCreator(factory);
                factory.setCreator(serverEndpointCreator);
            }
        });
        context.addServlet(closeEndpoint, "/ws");

        server.setHandler(new HandlerList(context.getCoreContextHandler(), new DefaultHandler()));
        JettyWebSocketServletContainerInitializer.configure(context, null);

        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.setIdleTimeout(Duration.ofSeconds(2));
        client.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    private void close(Session session)
    {
        if (session != null)
        {
            session.close();
        }
    }

    /**
     * Test fast close (bug #403817)
     *
     * @throws Exception on test failure
     */
    @Test
    public void fastClose() throws Exception
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("fastclose");
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try
        {
            session = futSession.get(5, SECONDS);

            // Verify that client got close
            clientEndpoint.assertReceivedCloseEvent(5000, is(StatusCode.NORMAL), containsString(""));

            // Verify that server socket got close event
            AbstractCloseEndpoint serverEndpoint = serverEndpointCreator.pollLastCreated();
            assertThat("Fast Close Latch", serverEndpoint.closeLatch.await(5, SECONDS), is(true));
            assertThat("Fast Close.statusCode", serverEndpoint.closeStatusCode, is(StatusCode.NORMAL));
        }
        finally
        {
            close(session);
        }
    }

    /**
     * Test fast fail (bug #410537)
     *
     * @throws Exception on test failure
     */
    @Test
    public void fastFail() throws Exception
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("fastfail");
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try (StacklessLogging ignore = new StacklessLogging(WebSocketCoreSession.class))
        {
            session = futSession.get(5, SECONDS);

            // Verify that client got close indicating SERVER_ERROR
            clientEndpoint.assertReceivedCloseEvent(5000, is(StatusCode.SERVER_ERROR), containsString("Intentional FastFail"));

            // Verify that server socket got close event
            AbstractCloseEndpoint serverEndpoint = serverEndpointCreator.pollLastCreated();
            serverEndpoint.assertReceivedCloseEvent(5000, is(StatusCode.SERVER_ERROR), containsString("Intentional FastFail"));

            // Validate errors (must be "java.lang.RuntimeException: Intentional Exception from onWebSocketConnect")
            assertThat("socket.onErrors", serverEndpoint.errors.size(), greaterThanOrEqualTo(1));
            Throwable cause = serverEndpoint.errors.poll(5, SECONDS);
            assertThat("Error type", cause, instanceOf(RuntimeException.class));
            // ... with optional ClosedChannelException
            cause = serverEndpoint.errors.peek();
            if (cause != null)
            {
                assertThat("Error type", cause, instanceOf(ClosedChannelException.class));
            }
        }
        finally
        {
            close(session);
        }
    }

    @Test
    public void dropConnection() throws Exception
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("container");
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try (StacklessLogging ignore = new StacklessLogging(WebSocketSession.class))
        {
            session = futSession.get(5, SECONDS);

            // Cause a client endpoint failure
            clientEndpoint.getEndPoint().close();

            // Verify that client got close
            clientEndpoint.assertReceivedCloseEvent(5000, is(StatusCode.ABNORMAL), containsString("Session Closed"));

            // Verify that server socket got close event
            AbstractCloseEndpoint serverEndpoint = serverEndpointCreator.pollLastCreated();
            serverEndpoint.assertReceivedCloseEvent(5000, is(StatusCode.ABNORMAL), containsString("Session Closed"));
        }
        finally
        {
            close(session);
        }
    }

    /**
     * Test session open session cleanup (bug #474936)
     *
     * @throws Exception on test failure
     */
    @Test
    public void testOpenSessionCleanup() throws Exception
    {
        //fastFail();
        //fastClose();
        //dropConnection();

        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("container");
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try (StacklessLogging ignore = new StacklessLogging(WebSocketSession.class))
        {
            session = futSession.get(5, SECONDS);

            session.getRemote().sendString("openSessions");

            String msg = clientEndpoint.messageQueue.poll(5, SECONDS);

            assertThat("Should only have 1 open session", msg, containsString("openSessions.size=1\n"));

            // Verify that client got close
            clientEndpoint.assertReceivedCloseEvent(5000, is(StatusCode.NORMAL), containsString("ContainerEndpoint"));

            // Verify that server socket got close event
            AbstractCloseEndpoint serverEndpoint = serverEndpointCreator.pollLastCreated();
            assertThat("Server Open Sessions Latch", serverEndpoint.closeLatch.await(5, SECONDS), is(true));
            assertThat("Server Open Sessions.statusCode", serverEndpoint.closeStatusCode, is(StatusCode.NORMAL));
            assertThat("Server Open Sessions.errors", serverEndpoint.errors.size(), is(0));
        }
        finally
        {
            close(session);
        }
    }

    @Test
    public void testSecondCloseFromOnClosed() throws Exception
    {
        // Testing WebSocketSession.close() in onClosed() does not cause deadlock.
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("closeInOnClose");
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        client.connect(clientEndpoint, wsUri, request).get(5, SECONDS);

        // Hard close from the server. Server onClosed() will try to close again which should be a NOOP.
        AbstractCloseEndpoint serverEndpoint = serverEndpointCreator.pollLastCreated();
        assertTrue(serverEndpoint.openLatch.await(5, SECONDS));
        serverEndpoint.getSession().close(StatusCode.SHUTDOWN, "SHUTDOWN hard close");

        // Verify that client got close
        clientEndpoint.assertReceivedCloseEvent(5000, is(StatusCode.SHUTDOWN), containsString("SHUTDOWN hard close"));

        // Verify that server socket got close event
        assertTrue(serverEndpoint.closeLatch.await(5, SECONDS));
        assertThat(serverEndpoint.closeStatusCode, is(StatusCode.SHUTDOWN));
    }

    @Test
    public void testSecondCloseFromOnClosedInNewThread() throws Exception
    {
        // Testing WebSocketSession.close() in onClosed() does not cause deadlock.
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("closeInOnCloseNewThread");
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        client.connect(clientEndpoint, wsUri, request).get(5, SECONDS);

        // Hard close from the server. Server onClosed() will try to close again which should be a NOOP.
        AbstractCloseEndpoint serverEndpoint = serverEndpointCreator.pollLastCreated();
        assertTrue(serverEndpoint.openLatch.await(5, SECONDS));
        serverEndpoint.getSession().close(StatusCode.SHUTDOWN, "SHUTDOWN hard close");

        // Verify that client got close
        clientEndpoint.assertReceivedCloseEvent(5000, is(StatusCode.SHUTDOWN), containsString("SHUTDOWN hard close"));

        // Verify that server socket got close event
        assertTrue(serverEndpoint.closeLatch.await(5, SECONDS));
        assertThat(serverEndpoint.closeStatusCode, is(StatusCode.SHUTDOWN));
    }
}
