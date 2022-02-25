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

package org.eclipse.jetty.servlet;

import java.io.IOException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This tests verifies that merging of queryStrings works when dispatching
 * Requests via {@link AsyncContext} multiple times.
 */
public class AsyncContextDispatchWithQueryStrings
{
    private Server _server = new Server();
    private ServletContextHandler _contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
    private LocalConnector _connector = new LocalConnector(_server);

    @BeforeEach
    public void setUp() throws Exception
    {
        _connector.setIdleTimeout(30000);
        _server.setConnectors(new Connector[]{_connector});

        _contextHandler.setContextPath("/");
        _contextHandler.addServlet(new ServletHolder(new TestServlet()), "/initialCall");
        _contextHandler.addServlet(new ServletHolder(new TestServlet()), "/firstDispatchWithNewQueryString");
        _contextHandler.addServlet(new ServletHolder(new TestServlet()), "/secondDispatchNewValueForExistingQueryString");

        _server.setHandler(new HandlerList(_contextHandler, new DefaultHandler()));
        _server.start();
    }

    @Test
    public void testMultipleDispatchesWithNewQueryStrings() throws Exception
    {
        String request =
            "GET /initialCall?initialParam=right HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Connection: close\r\n" + "\r\n";
        String responseString = _connector.getResponse(request);
        assertThat(responseString, startsWith("HTTP/1.1 200"));
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
        _server.join();
    }

    private class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String uri = request.getRequestURI();
            String queryString = request.getQueryString();
            if ("/initialCall".equals(uri))
            {
                AsyncContext async = request.startAsync();
                async.dispatch("/firstDispatchWithNewQueryString?newQueryString=initialValue");
                assertEquals("initialParam=right", queryString);
            }
            else if ("/firstDispatchWithNewQueryString".equals(uri))
            {
                AsyncContext async = request.startAsync();
                async.dispatch("/secondDispatchNewValueForExistingQueryString?newQueryString=newValue");
                assertEquals("newQueryString=initialValue", queryString);
            }
            else
            {
                response.setContentType("text/html");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().println("<h1>woohhooooo</h1>");
                assertEquals("newQueryString=newValue", queryString);
            }
        }
    }
}
