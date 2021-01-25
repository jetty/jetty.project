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

import java.net.URI;
import java.util.concurrent.Future;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.CloseTrackingEndpoint;
import org.eclipse.jetty.websocket.tests.EchoSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * This Regression Test Exists because of Client side Idle timeout, Read, and Parser bugs.
 */
public class SlowClientTest
{
    private Server server;
    private WebSocketClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.getPolicy().setIdleTimeout(60000);
        client.start();
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder websocket = new ServletHolder(new WebSocketServlet()
        {
            @Override
            public void configure(WebSocketServletFactory factory)
            {
                factory.register(EchoSocket.class);
            }
        });
        context.addServlet(websocket, "/ws");

        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);

        server.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testClientSlowToSend() throws Exception
    {
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();
        client.getPolicy().setIdleTimeout(60000);

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> future = client.connect(clientEndpoint, wsUri);

        // Confirm connected
        Session session = future.get(5, SECONDS);

        int messageCount = 10;
        try
        {
            // Have client write slowly.
            ClientWriteThread writer = new ClientWriteThread(clientEndpoint.getSession());
            writer.setMessageCount(messageCount);
            writer.setMessage("Hello");
            writer.setSlowness(10);
            writer.start();
            writer.join();

            // Close
            clientEndpoint.getSession().close(StatusCode.NORMAL, "Done");

            // confirm close received on server
            clientEndpoint.assertReceivedCloseEvent(10000, is(StatusCode.NORMAL), containsString("Done"));
        }
        finally
        {
            if (session != null)
            {
                session.close();
            }
        }
    }
}
