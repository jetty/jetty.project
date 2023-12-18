//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.websocket.api.Callback.NOOP;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerUpgradeRequestTest
{
    private Server _server;
    private ServerConnector _connector;
    private WebSocketClient _client;

    @WebSocket
    public static class MyEndpoint
    {
        @OnWebSocketOpen
        public void onOpen(Session session) throws Exception
        {
            UpgradeRequest upgradeRequest = session.getUpgradeRequest();
            session.sendText("userPrincipal=" + upgradeRequest.getUserPrincipal(), NOOP);
            session.sendText("requestURI=" + upgradeRequest.getRequestURI(), NOOP);
            session.close();
        }

        @OnWebSocketError
        public void onError(Throwable t)
        {
            t.printStackTrace();
        }
    }

    private static class TestLoginService extends AbstractLoginService
    {
        public TestLoginService(IdentityService identityService)
        {
            setIdentityService(identityService);
        }

        @Override
        protected List<RolePrincipal> loadRoleInfo(UserPrincipal user)
        {
            return List.of();
        }

        @Override
        protected UserPrincipal loadUserInfo(String username)
        {
            return new UserPrincipal(username, null)
            {
                @Override
                public boolean authenticate(Object credentials)
                {
                    return true;
                }

                @Override
                public boolean authenticate(Credential c)
                {
                    return true;
                }

                @Override
                public boolean authenticate(UserPrincipal u)
                {
                    return true;
                }
            };
        }
    }

    private static class TestAuthenticator extends LoginAuthenticator
    {
        @Override
        public String getAuthenticationType()
        {
            return "TEST";
        }

        @Override
        public AuthenticationState validateRequest(Request request, Response response, Callback callback)
        {
            UserIdentity user = login("user123", null, request, response);
            if (user != null)
                return new UserAuthenticationSucceeded(getAuthenticationType(), user);

            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
            return AuthenticationState.SEND_FAILURE;
        }
    }

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/context1");
        JettyWebSocketServletContainerInitializer.configure(contextHandler, ((servletContext, serverContainer) ->
        {
            serverContainer.addMapping("/ws", MyEndpoint.class);
        }));
        _server.setHandler(contextHandler);

        DefaultIdentityService identityService = new DefaultIdentityService();
        LoginService loginService = new TestLoginService(identityService);

        ConstraintMapping constraintMapping = new ConstraintMapping();
        constraintMapping.setPathSpec("/*");
        constraintMapping.setConstraint(Constraint.ANY_USER);

        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        securityHandler.setConstraintMappings(List.of(constraintMapping));
        securityHandler.setLoginService(loginService);
        securityHandler.setIdentityService(identityService);
        contextHandler.setSecurityHandler(securityHandler);
        securityHandler.setAuthenticator(new TestAuthenticator());

        _server.start();
        _client = new WebSocketClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void test() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/context1/ws");
        EventSocket clientEndpoint = new EventSocket();
        assertNotNull(_client.connect(clientEndpoint, uri));

        String msg = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(msg, equalTo("userPrincipal=user123"));

        msg = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(msg, equalTo("requestURI=ws://localhost:" + _connector.getLocalPort() + "/context1/ws"));

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.NORMAL));
    }
}
