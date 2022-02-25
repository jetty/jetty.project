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

package org.eclipse.jetty.websocket.jakarta.tests.server.examples;

import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class WebSocketServerExamplesTest
{
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketServerExamplesTest.class);

    @ClientEndpoint
    public static class ClientSocket
    {
        CountDownLatch closed = new CountDownLatch(1);
        ArrayBlockingQueue<String> messageQueue = new ArrayBlockingQueue<>(2);

        @OnOpen
        public void onOpen(Session sess)
        {
            LOG.debug("ClientSocket Connected: " + sess);
        }

        @OnMessage
        public void onMessage(String message)
        {
            messageQueue.offer(message);
            LOG.debug("Received TEXT message: " + message);
        }

        @OnClose
        public void onClose(CloseReason closeReason)
        {
            LOG.debug("ClientSocket Closed: " + closeReason);
            closed.countDown();
        }

        @OnError
        public void onError(Throwable cause)
        {
            LOG.debug("ClientSocket error", cause);
        }
    }

    static Server _server;
    static ServerConnector _connector;
    static ServletContextHandler _context;

    @BeforeAll
    public static void setup() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        _context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        _context.setContextPath("/");
        _context.setSecurityHandler(getSecurityHandler("user", "password", "testRealm"));
        _server.setHandler(_context);

        JakartaWebSocketServletContainerInitializer.configure(_context, (context, container) ->
        {
            container.addEndpoint(MyAuthedSocket.class);
            container.addEndpoint(StreamingEchoSocket.class);
            container.addEndpoint(GetHttpSessionSocket.class);
        });

        _server.start();
        System.setProperty("org.eclipse.jetty.websocket.port", Integer.toString(_connector.getLocalPort()));
    }

    @AfterAll
    public static void stop() throws Exception
    {
        _server.stop();
    }

    private static SecurityHandler getSecurityHandler(String username, String password, String realm)
    {

        HashLoginService loginService = new HashLoginService();
        UserStore userStore = new UserStore();
        userStore.addUser(username, Credential.getCredential(password), new String[]{"websocket"});
        loginService.setUserStore(userStore);
        loginService.setName(realm);

        Constraint constraint = new Constraint();
        constraint.setName("auth");
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[]{"**"});

        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/secured/socket/*");
        mapping.setConstraint(constraint);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        security.addConstraintMapping(mapping);
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);

        return security;
    }

    @Test
    public void testMyAuthedSocket() throws Exception
    {
        //HttpClient is configured for BasicAuthentication with the XmlHttpClientProvider
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/secured/socket");
        WebSocketContainer clientContainer = ContainerProvider.getWebSocketContainer();

        ClientSocket clientEndpoint = new ClientSocket();
        try (Session session = clientContainer.connectToServer(clientEndpoint, uri))
        {
            session.getBasicRemote().sendText("hello world");
        }
        clientEndpoint.closed.await(5, TimeUnit.SECONDS);

        String msg = clientEndpoint.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat(msg, is("hello world"));
    }

    @Test
    public void testStreamingEchoSocket() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/echo");
        WebSocketContainer clientContainer = ContainerProvider.getWebSocketContainer();

        ClientSocket clientEndpoint = new ClientSocket();
        try (Session session = clientContainer.connectToServer(clientEndpoint, uri))
        {
            session.getBasicRemote().sendText("hello world");
        }
        clientEndpoint.closed.await(5, TimeUnit.SECONDS);

        String msg = clientEndpoint.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat(msg, is("hello world"));
    }

    @Test
    public void testGetHttpSessionSocket() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/example");
        WebSocketContainer clientContainer = ContainerProvider.getWebSocketContainer();

        ClientSocket clientEndpoint = new ClientSocket();
        try (Session session = clientContainer.connectToServer(clientEndpoint, uri))
        {
            session.getBasicRemote().sendText("hello world");
        }
        clientEndpoint.closed.await(5, TimeUnit.SECONDS);

        String msg = clientEndpoint.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat(msg, is("hello world"));
    }
}
