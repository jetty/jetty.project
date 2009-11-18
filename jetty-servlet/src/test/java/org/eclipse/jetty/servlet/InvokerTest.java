// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.servlet;

import java.io.IOException;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;

/**
 *
 *
 */
public class InvokerTest extends TestCase
{
    Server _server;
    LocalConnector _connector;
    ServletContextHandler _context;

    protected void setUp() throws Exception
    {
        super.setUp();
        _server = new Server();
        _connector = new LocalConnector();
        _context = new ServletContextHandler(ServletContextHandler.SESSIONS);

        _server.setSendServerVersion(false);
        _server.addConnector(_connector);
        _server.setHandler(_context);

        _context.setContextPath("/");

        ServletHolder holder = _context.addServlet(Invoker.class, "/servlet/*");
        holder.setInitParameter("nonContextServlets","true");
        _server.start();
    }

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
