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

package org.eclipse.jetty.security;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.security.authentication.SPNEGOAuthenticator;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class SPNEGOAuthenticatorTest
{
    private Server _server;
    private LocalConnector _connector;

    @BeforeEach
    public void configureServer() throws Exception
    {
        _server = new Server();

        HttpConnectionFactory http = new HttpConnectionFactory();
        _connector = new LocalConnector(_server, http);
        _connector.setIdleTimeout(300000);

        _server.addConnector(_connector);

        ContextHandler contextHandler = new ContextHandler();

        contextHandler.setContextPath("/ctx");
        _server.setHandler(contextHandler);

        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        contextHandler.setHandler(securityHandler);

        securityHandler.put("/any/*", Constraint.ANY_USER);
        securityHandler.setAuthenticator(new SPNEGOAuthenticator());
        securityHandler.setHandler(new AuthenticationTestHandler());

        LoginService loginService = new AuthenticationTestHandler.CustomLoginService(new AuthenticationTestHandler.TestIdentityService());
        securityHandler.setLoginService(loginService);
        _server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (_server.isRunning())
        {
            _server.stop();
            _server.join();
        }
    }

    @Test
    public void testChallengeSentWithNoAuthorization() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("GET /ctx/any/thing HTTP/1.0\r\n\r\n"));

        assertThat(response.getStatus(), is(HttpStatus.UNAUTHORIZED_401));
        assertThat(response.get(HttpHeader.WWW_AUTHENTICATE), is(HttpHeader.NEGOTIATE.asString()));
    }

    @Test
    public void testChallengeSentWithUnhandledAuthorization() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/any/thing HTTP/1.0\r
            %s: Basic asdf\r
            \r
            """.formatted(HttpHeader.AUTHORIZATION)));

        assertThat(response.getStatus(), is(HttpStatus.UNAUTHORIZED_401));
        assertThat(response.get(HttpHeader.WWW_AUTHENTICATE), is(HttpHeader.NEGOTIATE.asString()));
    }

    @Test
    @Disabled // TODO this test needs a lot of work
    public void testChallengeBadResponse() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("""
            GET /ctx/any/thing HTTP/1.0\r
            %s: %s badtokenT\r
            \r
            """.formatted(HttpHeader.AUTHORIZATION, HttpHeader.NEGOTIATE)));

        assertThat(response.getStatus(), is(HttpStatus.UNAUTHORIZED_401));
        assertThat(response.get(HttpHeader.WWW_AUTHENTICATE), is(HttpHeader.NEGOTIATE.asString()));
    }
}

