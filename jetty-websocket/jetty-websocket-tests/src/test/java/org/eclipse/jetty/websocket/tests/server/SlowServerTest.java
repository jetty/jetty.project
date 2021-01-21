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

package org.eclipse.jetty.websocket.tests.server;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.CloseTrackingEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * This Regression Test Exists because of Server side Idle timeout, Write, and Generator bugs.
 */
public class SlowServerTest
{
    private Server server;
    private WebSocketClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.setMaxIdleTimeout(60000);
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
                factory.register(SlowServerEndpoint.class);
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
    public void testServerSlowToSend() throws Exception
    {
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();
        client.setMaxIdleTimeout(60000);

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> future = client.connect(clientEndpoint, wsUri);

        Session session = null;
        try
        {
            // Confirm connected
            session = future.get(5, SECONDS);

            int messageCount = 10;

            session.getRemote().sendString("send-slow|" + messageCount);

            // Verify receive
            LinkedBlockingQueue<String> responses = clientEndpoint.messageQueue;

            for (int i = 0; i < messageCount; i++)
            {
                String response = responses.poll(5, SECONDS);
                assertThat("Server Message[" + i + "]", response, is("Hello/" + i + "/"));
            }
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
