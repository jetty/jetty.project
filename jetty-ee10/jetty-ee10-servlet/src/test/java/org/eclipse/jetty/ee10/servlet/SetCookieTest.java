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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class SetCookieTest
{
    private Server server;
    private LocalConnector connector;

    public void startServer(ServletContextHandler contextHandler) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);
        server.setHandler(contextHandler);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testSetCookieAttribute() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");

        HttpServlet testServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                resp.setCharacterEncoding("utf-8");
                resp.setContentType("text/plain");
                Cookie cookie = new Cookie("key", "foo");
                cookie.setAttribute("SameSite", "Lax");
                resp.addCookie(cookie);

                resp.getWriter().printf("pathInfo: " + req.getPathInfo());
            }
        };

        contextHandler.addServlet(testServlet, "/test/*");
        startServer(contextHandler);

        String rawRequest = """
            GET /test/cookie-attr HTTP/1.1
            Host: test
            Connection: close
            
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertThat(response.getStatus(), is(200));
        assertThat(response.get(HttpHeader.SET_COOKIE), is("key=foo; SameSite=Lax"));
    }
}
