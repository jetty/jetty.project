//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.instanceOf;

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
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class EndpointEchoTest
{
    private static final Logger LOG = Log.getLogger(EndpointEchoTest.class);
    private static Server server;
    private static EchoHandler handler;
    private static URI serverUri;

    @BeforeClass
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
        serverUri = new URI(String.format("ws://%s:%d/",host,port));
    }

    @AfterClass
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
        EndpointEchoClient echoer = new EndpointEchoClient();
        Assert.assertThat(echoer,instanceOf(javax.websocket.Endpoint.class));
        // Issue connect using instance of class that extends Endpoint
        Session session = container.connectToServer(echoer,serverUri);
        if (LOG.isDebugEnabled())
            LOG.debug("Client Connected: {}",session);
        session.getBasicRemote().sendText("Echo");
        if (LOG.isDebugEnabled())
            LOG.debug("Client Message Sent");
        echoer.textCapture.messageQueue.awaitMessages(1,1000,TimeUnit.MILLISECONDS);
    }

    @Test
    public void testBasicEchoClassref() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        // Issue connect using class reference (class extends Endpoint)
        Session session = container.connectToServer(EndpointEchoClient.class,serverUri);
        if (LOG.isDebugEnabled())
            LOG.debug("Client Connected: {}",session);
        session.getBasicRemote().sendText("Echo");
        if (LOG.isDebugEnabled())
            LOG.debug("Client Message Sent");
        // TODO: figure out echo verification.
        // echoer.textCapture.messageQueue.awaitMessages(1,1000,TimeUnit.MILLISECONDS);
    }

    @Test
    public void testAbstractEchoInstance() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        EchoStringEndpoint echoer = new EchoStringEndpoint();
        Assert.assertThat(echoer,instanceOf(javax.websocket.Endpoint.class));
        // Issue connect using instance of class that extends abstract that extends Endpoint
        Session session = container.connectToServer(echoer,serverUri);
        if (LOG.isDebugEnabled())
            LOG.debug("Client Connected: {}",session);
        session.getBasicRemote().sendText("Echo");
        if (LOG.isDebugEnabled())
            LOG.debug("Client Message Sent");
        echoer.messageQueue.awaitMessages(1,1000,TimeUnit.MILLISECONDS);
    }

    @Test
    public void testAbstractEchoClassref() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        // Issue connect using class reference (class that extends abstract that extends Endpoint)
        Session session = container.connectToServer(EchoStringEndpoint.class,serverUri);
        if (LOG.isDebugEnabled())
            LOG.debug("Client Connected: {}",session);
        session.getBasicRemote().sendText("Echo");
        if (LOG.isDebugEnabled())
            LOG.debug("Client Message Sent");
        // TODO: figure out echo verification.
        // echoer.messageQueue.awaitMessages(1,1000,TimeUnit.MILLISECONDS);
    }
}
