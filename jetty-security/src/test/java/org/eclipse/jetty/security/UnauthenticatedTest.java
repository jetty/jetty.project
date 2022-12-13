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

package org.eclipse.jetty.security;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.authentication.DeferredAuthentication;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class UnauthenticatedTest
{
    private LocalConnector connector;
    private TestAuthenticator authenticator;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        Server server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        // Authenticator that always returns UNAUTHENTICATED.
        authenticator = new TestAuthenticator();

        // Add a security handler which requires paths under /requireAuth to be authenticated.
        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        Constraint requireAuthentication = new Constraint();
        requireAuthentication.setRoles(new String[]{"**"});
        requireAuthentication.setAuthenticate(true);
        ConstraintMapping constraintMapping = new ConstraintMapping();
        constraintMapping.setPathSpec("/requireAuth/*");
        constraintMapping.setConstraint(requireAuthentication);
        securityHandler.addConstraintMapping(constraintMapping);

        securityHandler.setAuthenticator(authenticator);
        securityHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(HttpStatus.OK_200);
                response.getWriter().println("authentication: " + baseRequest.getAuthentication());
            }
        });

        server.setHandler(securityHandler);
        server.start();
    }

    @Test
    public void testUnauthenticated() throws Exception
    {
        TestAuthenticator.AUTHENTICATION.set(Authentication.UNAUTHENTICATED);

        // Request to URI which doesn't require authentication can get through even though auth is UNAUTHENTICATED.
        String response = connector.getResponse("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("authentication: UNAUTHENTICATED"));

        // This URI requires just that the request is authenticated.
        response = connector.getResponse("GET /requireAuth/test HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 401 Unauthorized"));
    }

    @Test
    public void testDeferredAuth() throws Exception
    {
        TestAuthenticator.AUTHENTICATION.set(new DeferredAuthentication(authenticator));

        // Request to URI which doesn't require authentication can get through even though auth is UNAUTHENTICATED.
        String response = connector.getResponse("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("DeferredAuthentication"));

        // This URI requires just that the request is authenticated. But DeferredAuthentication can bypass this.
        response = connector.getResponse("GET /requireAuth/test HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("DeferredAuthentication"));
    }

    public static class TestAuthenticator extends LoginAuthenticator
    {
        static AtomicReference<Authentication> AUTHENTICATION = new AtomicReference<>();

        @Override
        public void setConfiguration(AuthConfiguration configuration)
        {
            // Do nothing.
        }

        @Override
        public String getAuthMethod()
        {
            return this.getClass().getSimpleName();
        }

        @Override
        public void prepareRequest(ServletRequest request)
        {
            // Do nothing.
        }

        @Override
        public Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory) throws ServerAuthException
        {
            return AUTHENTICATION.get();
        }

        @Override
        public boolean secureResponse(ServletRequest request, ServletResponse response, boolean mandatory, Authentication.User validatedUser) throws ServerAuthException
        {
            return true;
        }
    }
}
