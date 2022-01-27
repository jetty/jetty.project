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
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 *
 */
public class InvokerTest
{
    private Server _server;
    private LocalConnector _connector;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);
        _connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);

        _server.addConnector(_connector);
        _server.setHandler(context);

        context.setContextPath("/");

        ServletHolder holder = context.addServlet(Invoker.class, "/servlet/*");
        holder.setInitParameter("nonContextServlets", "true");
        _server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testInvoker() throws Exception
    {
        String requestPath = "/servlet/" + TestServlet.class.getName();
        String request = "GET " + requestPath + " HTTP/1.0\r\n" +
            "Host: tester\r\n" +
            "\r\n";

        String expectedResponse = "HTTP/1.1 200 OK\r\n" +
            "Content-Length: 20\r\n" +
            "\r\n" +
            "Invoked TestServlet!";

        String response = _connector.getResponse(request);
        assertEquals(expectedResponse, response);
    }

    public static class TestServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.getWriter().append("Invoked TestServlet!");
            response.getWriter().close();
        }
    }
}
