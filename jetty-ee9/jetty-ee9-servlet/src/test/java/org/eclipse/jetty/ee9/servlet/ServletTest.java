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

package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServletTest
{
    private Server _server;
    ServletContextHandler _context;
    ServletHandler _handler;
    private LocalConnector _connector;

    @BeforeEach
    public void beforeEach()
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        _context = new ServletContextHandler();
        _context.setContextPath("/ctx");

        _server.setHandler(_context);
        _handler = _context.getServletHandler();
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testGET() throws Exception
    {
        _context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.getWriter().println("Hello!");
            }
        }), "/get");

        _server.start();

        String response = _connector.getResponse("""
            GET /ctx/get HTTP/1.0
            
            """);
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Hello!"));
    }

    @Test
    public void testSimpleIdleIgnored() throws Exception
    {
        long idleTimeout = 1000;
        _context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                try
                {
                    Thread.sleep(2 * idleTimeout);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                resp.getWriter().println("Hello!");
            }
        }), "/get");

        _connector.setIdleTimeout(idleTimeout);
        _server.start();

        String response = _connector.getResponse("""
            GET /ctx/get HTTP/1.0
            
            """, 5 * idleTimeout, TimeUnit.MILLISECONDS);
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Hello!"));
    }

    @Test
    public void testSimpleIdleRead() throws Exception
    {
        long idleTimeout = 1000;
        _context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                String input = IO.toString(req.getInputStream());
                resp.getWriter().print("Hello " + input);
            }
        }), "/post");

        _connector.setIdleTimeout(idleTimeout);
        _server.start();

        try (LocalConnector.LocalEndPoint endPoint = _connector.connect())
        {
            String request = """
                POST /ctx/post HTTP/1.1
                Host: local
                Content-Length: 10
                
                """;
            endPoint.addInput(request);
            endPoint.addInput("1234567890");
            HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse(false, 5, TimeUnit.SECONDS));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), is("Hello 1234567890"));

            endPoint.addInputAndExecute(request);
            endPoint.addInput("1234567890");
            response = HttpTester.parseResponse(endPoint.getResponse(false, 5, TimeUnit.SECONDS));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), is("Hello 1234567890"));

            endPoint.addInputAndExecute(request);
            // Do not send the content.
            response = HttpTester.parseResponse(endPoint.getResponse(false, 2 * idleTimeout, TimeUnit.MILLISECONDS));
            assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
            assertTrue(response.contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString()));
        }
    }

    @Test
    public void testIdle() throws Exception
    {
        _context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                resp.getWriter().println("Hello!");
            }
        }), "/get");

        long idleTimeout = 1000;
        _connector.setIdleTimeout(idleTimeout);
        _server.start();

        try (LocalConnector.LocalEndPoint endPoint = _connector.connect())
        {
            String request = """
                GET /ctx/get HTTP/1.1
                Host: local
                            
                """;
            endPoint.addInput(request);
            String response = endPoint.getResponse(false, 5, TimeUnit.SECONDS);
            assertThat(response, containsString(" 200 OK"));
            assertThat(response, containsString("Hello!"));
            endPoint.addInput(request);
            response = endPoint.getResponse(false, 5, TimeUnit.SECONDS);
            assertThat(response, containsString(" 200 OK"));
            assertThat(response, containsString("Hello!"));

            Thread.sleep(2 * idleTimeout);

            assertFalse(endPoint.isOpen());
        }
    }
}
