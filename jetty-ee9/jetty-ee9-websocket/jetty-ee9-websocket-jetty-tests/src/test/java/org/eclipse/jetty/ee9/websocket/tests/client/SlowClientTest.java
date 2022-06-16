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

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Future;

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.StatusCode;
import org.eclipse.jetty.ee9.websocket.api.util.WSURI;
import org.eclipse.jetty.ee9.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee9.websocket.tests.CloseTrackingEndpoint;
import org.eclipse.jetty.ee9.websocket.tests.EchoSocket;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
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
        client.setIdleTimeout(Duration.ofSeconds(60));
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
        ServletHolder websocket = new ServletHolder(new JettyWebSocketServlet()
        {
            @Override
            public void configure(JettyWebSocketServletFactory factory)
            {
                factory.register(EchoSocket.class);
            }
        });
        context.addServlet(websocket, "/ws");

        server.setHandler(new HandlerList(context.getCoreContextHandler(), new DefaultHandler()));
        JettyWebSocketServletContainerInitializer.configure(context, null);

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
        client.setIdleTimeout(Duration.ofSeconds(60));

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
