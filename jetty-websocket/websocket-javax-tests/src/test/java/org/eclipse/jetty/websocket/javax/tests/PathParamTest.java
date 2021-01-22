//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.websocket.ContainerProvider;
import javax.websocket.OnMessage;
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
        {
            container.addEndpoint(StringParamSocket.class);
            container.addEndpoint(IntegerParamSocket.class);
            container.addEndpoint(IntParamSocket.class);
            container.addEndpoint(WSFullBooleanAndSessionAndPathParamServer.class);
        });

        _server.start();
    }

    @AfterEach
    public void stopContainer() throws Exception
    {
        _server.stop();
    }

    @ServerEndpoint("/pathParam/string/{param}")
    public static class StringParamSocket
    {
        @OnMessage
        public void onMessage(Session session, String message, @PathParam("param") String param)
        {
            session.getAsyncRemote().sendText(message + "-" + param);
        }
    }

    @ServerEndpoint("/pathParam/integer/{param}")
    public static class IntegerParamSocket
    {
        @OnMessage
        public void onMessage(Session session, String message, @PathParam("param") Integer param)
        {
            session.getAsyncRemote().sendText(message + "-" + param);
        }
    }

    @ServerEndpoint("/pathParam/int/{param}")
    public static class IntParamSocket
    {
        @OnMessage
        public void onMessage(Session session, String message, @PathParam("param") int param)
        {
            session.getAsyncRemote().sendText(message + "-" + param);
        }
    }

    @ServerEndpoint("/pathParam/paramInBrackets/{param}")
    public static class WSFullBooleanAndSessionAndPathParamServer
    {
        @OnMessage
        public String echo(@PathParam("{param}") Boolean param, Boolean b, Session s)
        {
            return "message:" + b + ", param:" + param;
        }
    }

    @Test
    public void testStringPathParamSocket() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        EventSocket clientEndpoint = new EventSocket();

        URI serverUri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/context/pathParam/string/myParam");
        Session session = container.connectToServer(clientEndpoint, serverUri);
        session.getBasicRemote().sendText("echo");

        String resp = clientEndpoint.textMessages.poll(1, TimeUnit.SECONDS);
        assertThat("Response echo", resp, is("echo-myParam"));
        session.close();
        clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS);
    }

    @Test
    public void testIntegerPathParamSocket() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        EventSocket clientEndpoint = new EventSocket();

        URI serverUri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/context/pathParam/integer/1001");
        Session session = container.connectToServer(clientEndpoint, serverUri);
        session.getBasicRemote().sendText("echo");

        String resp = clientEndpoint.textMessages.poll(1, TimeUnit.SECONDS);
        assertThat("Response echo", resp, is("echo-1001"));
        session.close();
        clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS);
    }

    @Test
    public void testIntPathParamSocket() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        EventSocket clientEndpoint = new EventSocket();

        URI serverUri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/context/pathParam/int/1001");
        Session session = container.connectToServer(clientEndpoint, serverUri);
        session.getBasicRemote().sendText("echo");

        String resp = clientEndpoint.textMessages.poll(1, TimeUnit.SECONDS);
        assertThat("Response echo", resp, is("echo-1001"));
        session.close();
        clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS);
    }

    @Test
    public void testPathPramStripsBrackets() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        EventSocket clientEndpoint = new EventSocket();

        URI serverUri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/context/pathParam/paramInBrackets/false");
        Session session = container.connectToServer(clientEndpoint, serverUri);
        session.getBasicRemote().sendText("true");

        String resp = clientEndpoint.textMessages.poll(1, TimeUnit.SECONDS);
        assertThat("Response echo", resp, is("message:true, param:false"));
        session.close();
        clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS);
    }
}
