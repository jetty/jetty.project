//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.websocket.ContainerProvider;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class PathParamTest
{
    private Server _server;
    private ServerConnector _connector;
    private ServletContextHandler _context;

    @BeforeEach
    public void startContainer() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        _context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        _context.setContextPath("/context");
        _server.setHandler(_context);

        JavaxWebSocketServletContainerInitializer.configure(_context, (context, container) ->
            container.addEndpoint(EchoParamSocket.class));

        _server.start();
    }

    @AfterEach
    public void stopContainer() throws Exception
    {
        _server.stop();
    }

    @ServerEndpoint("/pathParam/echo/{name}")
    public static class EchoParamSocket
    {
        private Session session;

        @OnOpen
        public void onOpen(Session session)
        {
            this.session = session;
        }

        @OnMessage
        public void onMessage(String message, @PathParam("name") String name)
        {
            session.getAsyncRemote().sendText(message + "-" + name);
        }
    }

    @Test
    public void testBasicPathParamSocket() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        EventSocket clientEndpoint = new EventSocket();

        URI serverUri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/context/pathParam/echo/myParam");
        Session session = container.connectToServer(clientEndpoint, serverUri);
        session.getBasicRemote().sendText("echo");

        String resp = clientEndpoint.textMessages.poll(1, TimeUnit.SECONDS);
        assertThat("Response echo", resp, is("echo-myParam"));
        session.close();
        clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS);
    }
}
