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

package org.eclipse.jetty.ee10.servlet.security;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.EmptyLoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class FormAuthenticatorTest
{
    private Server _server;
    private LocalConnector _connector;

    @BeforeEach
    public void configureServer() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler contextHandler = new ServletContextHandler("/ctx", ServletContextHandler.SESSIONS);
        _server.setHandler(contextHandler);
        contextHandler.addServlet(new AuthenticationTestServlet(), "/");

        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        securityHandler.setLoginService(new EmptyLoginService());
        contextHandler.insertHandler(securityHandler);
        securityHandler.put("/any/*", Constraint.ANY_USER);
        securityHandler.put("/known/*", Constraint.KNOWN_ROLE);
        securityHandler.put("/admin/*", Constraint.from("admin"));
        securityHandler.setAuthenticator(new FormAuthenticator("/login", "/error", true));

        _server.start();
    }

    public static class AuthenticationTestServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            req.getSession();
            PrintWriter writer = resp.getWriter();
            writer.println("contextPath: " + req.getContextPath());
            writer.println("servletPath: " + req.getServletPath());
            writer.println("dispatcherType: " + req.getDispatcherType());
        }
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
    public void testLoginDispatch() throws Exception
    {
        String response = _connector.getResponse("GET /ctx/admin/user HTTP/1.0\r\nHost:host:8888\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("dispatcherType: REQUEST"));
        assertThat(response, containsString("contextPath: /ctx"));
        assertThat(response, containsString("servletPath: /login"));
    }

    @Test
    public void testErrorDispatch() throws Exception
    {
        String response = _connector.getResponse("GET /ctx/j_security_check?j_username=user&j_password=wrong HTTP/1.0\r\nHost:host:8888\r\n\r\n");
        assertThat(response, containsString("dispatcherType: REQUEST"));
        assertThat(response, containsString("contextPath: /ctx"));
        assertThat(response, containsString("servletPath: /error"));
    }
}
