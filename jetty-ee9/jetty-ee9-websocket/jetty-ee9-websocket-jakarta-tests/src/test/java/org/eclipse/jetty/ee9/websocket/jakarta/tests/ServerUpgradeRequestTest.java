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

package org.eclipse.jetty.ee9.websocket.jakarta.tests;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee9.nested.Authentication;
import org.eclipse.jetty.ee9.nested.ServletConstraint;
import org.eclipse.jetty.ee9.security.ConstraintMapping;
import org.eclipse.jetty.ee9.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee9.security.ServerAuthException;
import org.eclipse.jetty.ee9.security.UserAuthentication;
import org.eclipse.jetty.ee9.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.websocket.jakarta.client.JakartaWebSocketClientContainer;
import org.eclipse.jetty.ee9.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.security.Credential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.ee9.nested.ServletConstraint.ANY_AUTH;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerUpgradeRequestTest
{
    private Server _server;
    private ServerConnector _connector;
    private JakartaWebSocketClientContainer _client;

    @ServerEndpoint("/ws")
    public static class MyEndpoint
    {
        @OnOpen
        public void onOpen(Session session) throws Exception
        {
            session.getBasicRemote().sendText("userPrincipal=" + session.getUserPrincipal());
            session.getBasicRemote().sendText("requestURI=" + session.getRequestURI());
            session.close();
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
        public String getAuthMethod()
        {
            return "TEST";
        }

        @Override
        public Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory) throws ServerAuthException
        {
            UserIdentity user = login("user123", null, request);
            if (user != null)
                return new UserAuthentication("TEST", user);
            return Authentication.UNAUTHENTICATED;
        }

        @Override
        public boolean secureResponse(ServletRequest request, ServletResponse response, boolean mandatory, Authentication.User validatedUser) throws ServerAuthException
        {
            return request.isSecure();
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
        JakartaWebSocketServletContainerInitializer.configure(contextHandler, ((servletContext, serverContainer) ->
        {
            serverContainer.addEndpoint(MyEndpoint.class);
        }));
        _server.setHandler(contextHandler);

        DefaultIdentityService identityService = new DefaultIdentityService();
        LoginService loginService = new TestLoginService(identityService);

        ConstraintMapping constraintMapping = new ConstraintMapping();
        constraintMapping.setPathSpec("/*");
        constraintMapping.setConstraint(new ServletConstraint("constraint1", ANY_AUTH));

        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        securityHandler.setConstraintMappings(List.of(constraintMapping));
        securityHandler.setLoginService(loginService);
        securityHandler.setIdentityService(identityService);
        contextHandler.setSecurityHandler(securityHandler);
        securityHandler.setAuthenticator(new TestAuthenticator());

        _server.start();
        _client = new JakartaWebSocketClientContainer();
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
        assertNotNull(_client.connectToServer(clientEndpoint, uri));

        String msg = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(msg, equalTo("userPrincipal=user123"));

        msg = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(msg, equalTo("requestURI=ws://localhost:" + _connector.getLocalPort() + "/context1/ws"));

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), equalTo(CloseReason.CloseCodes.NORMAL_CLOSURE));
    }
}
