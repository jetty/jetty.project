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

package org.eclipse.jetty.ee10.servlet.security.authentication;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee10.servlet.security.EmptyLoginService;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.security.Constraint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class SpnegoAuthenticatorTest
{
    private Server _server;
    private LocalConnector _localConnector;

    @BeforeEach
    public void setup() throws Exception
    {
        ConfigurableSpnegoAuthenticator authenticator = new ConfigurableSpnegoAuthenticator();
        _server = new Server();
        _localConnector = new LocalConnector(_server);
        _server.addConnector(_localConnector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        _server.setHandler(contextHandler);
        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        contextHandler.setSecurityHandler(securityHandler);
        securityHandler.setAuthenticator(authenticator);
        securityHandler.setLoginService(new EmptyLoginService());
        Constraint adminConstraint = new Constraint();
        adminConstraint.setName(Constraint.__OPENID_AUTH);
        adminConstraint.setRoles(new String[]{"admin"});
        adminConstraint.setAuthenticate(true);
        ConstraintMapping adminMapping = new ConstraintMapping();
        adminMapping.setConstraint(adminConstraint);
        adminMapping.setPathSpec("/*");
        securityHandler.addConstraintMapping(adminMapping);
        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testChallengeSentWithNoAuthorization() throws Exception
    {
        String response = _localConnector.getResponse("GET / HTTP/1.1\r\nHost:localhost\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 401 Unauthorized"));
        assertThat(response, containsString("WWW-Authenticate: Negotiate"));
    }

    @Test
    public void testChallengeSentWithUnhandledAuthorization() throws Exception
    {
        // Create a bogus Authorization header. We don't care about the actual credentials.
        String response = _localConnector.getResponse("GET / HTTP/1.1\r\nHost:localhost\r\nAuthorization:basic asdf\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 401 Unauthorized"));
        assertThat(response, containsString("WWW-Authenticate: Negotiate"));
    }
}

