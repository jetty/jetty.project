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

package org.eclipse.jetty.ee9.security.jaspi;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.AbstractHandler;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.nested.Request;
import org.eclipse.jetty.ee9.security.AbstractLoginService;
import org.eclipse.jetty.ee9.security.ConstraintMapping;
import org.eclipse.jetty.ee9.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee9.security.RolePrincipal;
import org.eclipse.jetty.ee9.security.UserPrincipal;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Password;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;

public class JaspiTest
{
    Server _server;
    LocalConnector _connector;

    public static class TestLoginService extends AbstractLoginService
    {
        protected Map<String, UserPrincipal> _users = new HashMap<>();
        protected Map<String, List<RolePrincipal>> _roles = new HashMap<>();

        public TestLoginService(String name)
        {
            setName(name);
        }

        public void putUser(String username, Credential credential, String[] roles)
        {
            UserPrincipal userPrincipal = new UserPrincipal(username, credential);
            _users.put(username, userPrincipal);
            if (roles != null)
            {
                List<RolePrincipal> rps = Arrays.stream(roles).map(RolePrincipal::new).collect(Collectors.toList());
                _roles.put(username, rps);
            }
        }

        @Override
        protected List<RolePrincipal> loadRoleInfo(UserPrincipal user)
        {
            return _roles.get(user.getName());
        }

        @Override
        protected UserPrincipal loadUserInfo(String username)
        {
            return _users.get(username);
        }
    }

    @BeforeAll
    public static void beforeAll() throws Exception
    {
        AuthConfigFactory factory = new DefaultAuthConfigFactory();

        factory.registerConfigProvider("org.eclipse.jetty.ee9.security.jaspi.provider.JaspiAuthConfigProvider",
            Map.of("ServerAuthModule", "org.eclipse.jetty.ee9.security.jaspi.modules.BasicAuthenticationAuthModule",
                "AppContextID", "server /ctx",
                "org.eclipse.jetty.ee9.security.jaspi.modules.RealmName", "TestRealm"),
            "HttpServlet", "server /ctx", "a test provider");

        factory.registerConfigProvider("org.eclipse.jetty.ee9.security.jaspi.provider.JaspiAuthConfigProvider",
            Map.of("ServerAuthModule", "org.eclipse.jetty.ee9.security.jaspi.HttpHeaderAuthModule",
                "AppContextID", "server /other"),
            "HttpServlet", "server /other", "another test provider");

        AuthConfigFactory.setFactory(factory);
    }

    @AfterAll
    public static void afterAll() throws Exception
    {
        AuthConfigFactory.setFactory(null);
    }

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        TestLoginService loginService = new TestLoginService("TestRealm");
        loginService.putUser("user", new Password("password"), new String[] {"users"});
        loginService.putUser("admin", new Password("secret"), new String[] {"users", "admins"});
        _server.addBean(loginService);

        ContextHandler context = new ContextHandler();
        contexts.addHandler(context);
        context.setContextPath("/ctx");

        JaspiAuthenticatorFactory jaspiAuthFactory = new JaspiAuthenticatorFactory();

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        context.setHandler(security);
        security.setAuthenticatorFactory(jaspiAuthFactory);
        // security.setAuthenticator(new BasicAuthenticator());

        Constraint constraint = new Constraint("All", "users");
        constraint.setAuthenticate(true);
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/jaspi/*");
        mapping.setConstraint(constraint);
        security.addConstraintMapping(mapping);

        TestHandler handler = new TestHandler();
        security.setHandler(handler);

        ContextHandler other = new ContextHandler();
        contexts.addHandler(other);
        other.setContextPath("/other");
        ConstraintSecurityHandler securityOther = new ConstraintSecurityHandler();
        other.setHandler(securityOther);
        securityOther.setAuthenticatorFactory(jaspiAuthFactory);
        securityOther.addConstraintMapping(mapping);
        securityOther.setHandler(new TestHandler());

        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testNoConstraint() throws Exception
    {
        String response = _connector.getResponse("GET /ctx/test HTTP/1.0\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testConstraintNoAuth() throws Exception
    {
        String response = _connector.getResponse("GET /ctx/jaspi/test HTTP/1.0\n\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        assertThat(response, Matchers.containsString("WWW-Authenticate: basic realm=\"TestRealm\""));
    }

    @Test
    public void testConstraintWrongAuth() throws Exception
    {
        String response = _connector.getResponse("GET /ctx/jaspi/test HTTP/1.0\n" + "Authorization: Basic " +
                Base64.getEncoder().encodeToString("user:wrong".getBytes(ISO_8859_1)) + "\n\n");
        assertThat(response, startsWith("HTTP/1.1 401 Unauthorized"));
        assertThat(response, Matchers.containsString("WWW-Authenticate: basic realm=\"TestRealm\""));
    }

    @Test
    public void testConstraintAuth() throws Exception
    {
        String response = _connector.getResponse("GET /ctx/jaspi/test HTTP/1.0\n" + "Authorization: Basic " +
                Base64.getEncoder().encodeToString("user:password".getBytes(ISO_8859_1)) + "\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testOtherNoAuth() throws Exception
    {
        String response = _connector.getResponse("GET /other/test HTTP/1.0\n\n");
        assertThat(response, startsWith("HTTP/1.1 403 Forbidden"));
    }

    @Test
    public void testOtherAuth() throws Exception
    {
        String response = _connector.getResponse("GET /other/test HTTP/1.0\n" + "X-Forwarded-User: user\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    public class TestHandler extends AbstractHandler
    {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            response.setContentType("text/plain");
            response.getWriter().println("All OK");
            response.getWriter().println("requestURI=" + request.getRequestURI());
        }
    }
}
