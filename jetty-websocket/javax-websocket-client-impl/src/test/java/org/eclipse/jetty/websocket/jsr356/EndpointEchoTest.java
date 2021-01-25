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

package org.eclipse.jetty.websocket.jsr356;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.websocket.ContainerProvider;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.jsr356.samples.EchoStringEndpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class EndpointEchoTest
{
    private static final Logger LOG = Log.getLogger(EndpointEchoTest.class);
    private static Server server;
    private static EchoHandler handler;
    private static URI serverUri;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        handler = new EchoHandler();

        ContextHandler context = new ContextHandler();
        context.setContextPath("/");
        context.setHandler(handler);
        server.setHandler(context);

        // Start Server
        server.start();

        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverUri = new URI(String.format("ws://%s:%d/", host, port));
    }

    @AfterAll
    public static void stopServer()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }
    }

    @Test
    public void testBasicEchoInstance() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server
        EndpointEchoClient echoer = new EndpointEchoClient();
        assertThat(echoer, instanceOf(javax.websocket.Endpoint.class));
        // Issue connect using instance of class that extends Endpoint
        Session session = container.connectToServer(echoer, serverUri);
        if (LOG.isDebugEnabled())
            LOG.debug("Client Connected: {}", session);
        session.getBasicRemote().sendText("Echo");
        if (LOG.isDebugEnabled())
            LOG.debug("Client Message Sent");
        String echoed = echoer.textCapture.messages.poll(1, TimeUnit.SECONDS);
        assertThat("Echoed message", echoed, is("Echo"));
    }

    @Test
    public void testBasicEchoClassref() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server
        // Issue connect using class reference (class extends Endpoint)
        Session session = container.connectToServer(EndpointEchoClient.class, serverUri);
        if (LOG.isDebugEnabled())
            LOG.debug("Client Connected: {}", session);
        session.getBasicRemote().sendText("Echo");
        if (LOG.isDebugEnabled())
            LOG.debug("Client Message Sent");
        EndpointEchoClient client = (EndpointEchoClient)session.getUserProperties().get("endpoint");
        String echoed = client.textCapture.messages.poll(1, TimeUnit.SECONDS);
        assertThat("Echoed message", echoed, is("Echo"));
    }

    @Test
    public void testAbstractEchoInstance() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server
        EchoStringEndpoint echoer = new EchoStringEndpoint();
        assertThat(echoer, instanceOf(javax.websocket.Endpoint.class));
        // Issue connect using instance of class that extends abstract that extends Endpoint
        Session session = container.connectToServer(echoer, serverUri);
        if (LOG.isDebugEnabled())
            LOG.debug("Client Connected: {}", session);
        session.getBasicRemote().sendText("Echo");
        if (LOG.isDebugEnabled())
            LOG.debug("Client Message Sent");
        String echoed = echoer.messages.poll(1, TimeUnit.SECONDS);
        assertThat("Echoed message", echoed, is("Echo"));
    }

    @Test
    public void testAbstractEchoClassref() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server
        // Issue connect using class reference (class that extends abstract that extends Endpoint)
        Session session = container.connectToServer(EchoStringEndpoint.class, serverUri);
        if (LOG.isDebugEnabled())
            LOG.debug("Client Connected: {}", session);
        session.getBasicRemote().sendText("Echo");
        if (LOG.isDebugEnabled())
            LOG.debug("Client Message Sent");
        EchoStringEndpoint client = (EchoStringEndpoint)session.getUserProperties().get("endpoint");
        String echoed = client.messages.poll(1, TimeUnit.SECONDS);
        assertThat("Echoed message", echoed, is("Echo"));
    }
}
