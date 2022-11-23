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

package org.eclipse.jetty.proxy;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.rewrite.handler.VirtualHostRuleContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.session.DefaultSessionIdManager;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class BalancerServletTest
{
    private static final String CONTEXT_PATH = "/context";
    private static final String SERVLET_PATH = "/mapping";

    private boolean stickySessions;
    private Server server1;
    private Server server2;
    private Server balancer;
    private HttpClient client;

    @BeforeEach
    public void prepare() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        server1.stop();
        server2.stop();
        balancer.stop();
        client.stop();
    }

    protected void startBalancer(Class<? extends HttpServlet> servletClass) throws Exception
    {
        server1 = createServer(new ServletHolder(servletClass), "node1");
        server1.start();

        server2 = createServer(new ServletHolder(servletClass), "node2");
        server2.start();

        ServletHolder balancerServletHolder = new ServletHolder(BalancerServlet.class);
        balancerServletHolder.setInitParameter("stickySessions", String.valueOf(stickySessions));
        balancerServletHolder.setInitParameter("proxyPassReverse", "true");
        balancerServletHolder.setInitParameter("balancerMember." + "node1" + ".proxyTo", "http://localhost:" + getServerPort(server1));
        balancerServletHolder.setInitParameter("balancerMember." + "node2" + ".proxyTo", "http://localhost:" + getServerPort(server2));

        balancer = createServer(balancerServletHolder, null);
        balancer.start();
    }

    private Server createServer(ServletHolder servletHolder, String nodeName)
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, CONTEXT_PATH, ServletContextHandler.SESSIONS);
        context.addServlet(servletHolder, SERVLET_PATH + "/*");

        if (nodeName != null)
        {
            DefaultSessionIdManager sessionIdManager = new DefaultSessionIdManager(server);
            sessionIdManager.setWorkerName(nodeName);
        }

        return server;
    }

    private int getServerPort(Server server)
    {
        return server.getURI().getPort();
    }

    protected ContentResponse getBalancedResponse(String path) throws Exception
    {
        return client.newRequest("localhost", getServerPort(balancer))
            .path(CONTEXT_PATH + SERVLET_PATH + path)
            .timeout(5, TimeUnit.SECONDS)
            .send();
    }

    protected byte[] sendRequestToBalancer(String path) throws Exception
    {
        ContentResponse response = getBalancedResponse(path);
        return response.getContent();
    }

    @Test
    public void testRoundRobinBalancer() throws Exception
    {
        stickySessions = false;
        startBalancer(CounterServlet.class);
        for (int i = 0; i < 10; i++)
        {
            byte[] responseBytes = sendRequestToBalancer("/roundRobin");
            String returnedCounter = readFirstLine(responseBytes);
            // Counter should increment every other request
            String expectedCounter = String.valueOf(i / 2);
            assertEquals(expectedCounter, returnedCounter);
        }
    }

    @Test
    public void testStickySessionsBalancer() throws Exception
    {
        stickySessions = true;
        startBalancer(CounterServlet.class);
        for (int i = 0; i < 10; i++)
        {
            byte[] responseBytes = sendRequestToBalancer("/stickySessions");
            String returnedCounter = readFirstLine(responseBytes);
            // Counter should increment every request
            String expectedCounter = String.valueOf(i);
            assertEquals(expectedCounter, returnedCounter);
        }
    }

    @Test
    public void testProxyPassReverse() throws Exception
    {
        stickySessions = false;
        startBalancer(RelocationServlet.class);
        byte[] responseBytes = sendRequestToBalancer("/index.html");
        String msg = readFirstLine(responseBytes);
        assertEquals("success", msg);
    }

    @Test
    public void testRewrittenBalancerWithEncodedURI() throws Exception
    {
        startBalancer(DumpServlet.class);
        balancer.stop();
        RewriteHandler rewrite = new RewriteHandler();
        rewrite.setHandler(balancer.getHandler());
        balancer.setHandler(rewrite);
        rewrite.setRewriteRequestURI(true);
        rewrite.addRule(new VirtualHostRuleContainer());
        balancer.start();

        ContentResponse response = getBalancedResponse("/test/%0A");
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentAsString(), containsString("requestURI='/context/mapping/test/%0A'"));
        assertThat(response.getContentAsString(), containsString("servletPath='/mapping'"));
        assertThat(response.getContentAsString(), containsString("pathInfo='/test/\n'"));
    }

    private String readFirstLine(byte[] responseBytes) throws IOException
    {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(responseBytes)));
        return reader.readLine();
    }

    public static final class DumpServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setContentType("text/plain");
            resp.getWriter().printf("requestURI='%s'%n", req.getRequestURI());
            resp.getWriter().printf("servletPath='%s'%n", req.getServletPath());
            resp.getWriter().printf("pathInfo='%s'%n", req.getPathInfo());
            resp.getWriter().flush();
        }
    }

    public static final class CounterServlet extends HttpServlet
    {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            // Force session creation
            req.getSession();
            resp.setContentType("text/plain");
            resp.getWriter().print(counter.getAndIncrement());
        }
    }

    public static final class RelocationServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            if (req.getRequestURI().endsWith("/index.html"))
            {
                resp.sendRedirect("http://localhost:" + req.getLocalPort() + req.getContextPath() + req.getServletPath() + "/other.html?secret=pipo+molo");
            }
            else
            {
                resp.setContentType("text/plain");
                if ("pipo molo".equals(req.getParameter("secret")))
                    resp.getWriter().println("success");
            }
        }
    }
}
