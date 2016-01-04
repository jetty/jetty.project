//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.servlet;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class InvokerTest
{
    private Server _server;
    private LocalConnector _connector;

    @Before
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
        holder.setInitParameter("nonContextServlets","true");
        _server.start();
    }

    @After
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testInvoker() throws Exception
    {
        String requestPath = "/servlet/"+TestServlet.class.getName();
        String request =  "GET "+requestPath+" HTTP/1.0\r\n"+
            "Host: tester\r\n"+
            "\r\n";

        String expectedResponse = "HTTP/1.1 200 OK\r\n" +
            "Content-Length: 20\r\n" +
            "\r\n" +
            "Invoked TestServlet!";

        String response = _connector.getResponses(request);
        assertEquals(expectedResponse, response);
    }

    public static class TestServlet extends HttpServlet implements Servlet
    {
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.getWriter().append("Invoked TestServlet!");
            response.getWriter().close();
        }
    }
}
