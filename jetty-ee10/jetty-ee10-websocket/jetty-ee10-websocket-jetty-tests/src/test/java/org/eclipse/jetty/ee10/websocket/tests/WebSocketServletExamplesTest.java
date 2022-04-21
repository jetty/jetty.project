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

package org.eclipse.jetty.ee10.websocket.tests;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee10.servlet.security.HashLoginService;
import org.eclipse.jetty.ee10.servlet.security.SecurityHandler;
import org.eclipse.jetty.ee10.servlet.security.UserStore;
import org.eclipse.jetty.ee10.servlet.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee10.websocket.tests.examples.MyAdvancedEchoServlet;
import org.eclipse.jetty.ee10.websocket.tests.examples.MyAuthedServlet;
import org.eclipse.jetty.ee10.websocket.tests.examples.MyEchoServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketServletExamplesTest
{
    private Server _server;
    private ServerConnector connector;
    private ServletContextHandler _context;

    @BeforeEach
    public void setup() throws Exception
    {
        _server = new Server();
        connector = new ServerConnector(_server);
        _server.addConnector(connector);

        _context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        _context.setContextPath("/");
        _context.setSecurityHandler(getSecurityHandler("user", "password", "testRealm"));
        _server.setHandler(_context);

        _context.addServlet(MyEchoServlet.class, "/echo");
        _context.addServlet(MyAdvancedEchoServlet.class, "/advancedEcho");
        _context.addServlet(MyAuthedServlet.class, "/authed");

        JettyWebSocketServletContainerInitializer.configure(_context, null);
        _server.start();
    }

    @AfterEach
    public void stop() throws Exception
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
        mapping.setPathSpec("/authed/*");
        mapping.setConstraint(constraint);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        security.addConstraintMapping(mapping);
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);

        return security;
    }

    @Test
    public void testEchoServlet() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/echo");
        EventSocket socket = new EventSocket();
        CompletableFuture<Session> connect = client.connect(socket, uri);
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            String message = "hello world";
            session.getRemote().sendString(message);

            String response = socket.textMessages.poll(5, TimeUnit.SECONDS);
            assertThat(response, is(message));
        }

        assertTrue(socket.closeLatch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testAdvancedEchoServlet() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/advancedEcho");
        EventSocket socket = new EventSocket();

        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("text");
        CompletableFuture<Session> connect = client.connect(socket, uri, upgradeRequest);
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            String message = "hello world";
            session.getRemote().sendString(message);

            String response = socket.textMessages.poll(5, TimeUnit.SECONDS);
            assertThat(response, is(message));
        }

        assertTrue(socket.closeLatch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testAuthedServlet() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();
        AuthenticationStore authenticationStore = client.getHttpClient().getAuthenticationStore();

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/authed");

        BasicAuthentication basicAuthentication = new BasicAuthentication(uri, "testRealm", "user", "password");
        authenticationStore.addAuthentication(basicAuthentication);

        EventSocket socket = new EventSocket();
        CompletableFuture<Session> connect = client.connect(socket, uri);
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            String message = "hello world";
            session.getRemote().sendString(message);

            String response = socket.textMessages.poll(5, TimeUnit.SECONDS);
            assertThat(response, is(message));
        }

        assertTrue(socket.closeLatch.await(10, TimeUnit.SECONDS));
    }
}
