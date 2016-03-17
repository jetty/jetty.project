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

package org.eclipse.jetty.proxy;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.session.DefaultSessionIdManager;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BalancerServletTest
{
    private static final String CONTEXT_PATH = "/context";
    private static final String SERVLET_PATH = "/mapping";

    private boolean stickySessions;
    private Server server1;
    private Server server2;
    private Server balancer;
    private HttpClient client;

    @Before
    public void prepare() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @After
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
            server.setSessionIdManager(sessionIdManager);
        }

        return server;
    }

    private int getServerPort(Server server)
    {
        return server.getURI().getPort();
    }

    protected byte[] sendRequestToBalancer(String path) throws Exception
    {
        ContentResponse response = client.newRequest("localhost", getServerPort(balancer))
                .path(CONTEXT_PATH + SERVLET_PATH + path)
                .timeout(5, TimeUnit.SECONDS)
                .send();
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
            Assert.assertEquals(expectedCounter, returnedCounter);
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
            Assert.assertEquals(expectedCounter, returnedCounter);
        }
    }

    @Test
    public void testProxyPassReverse() throws Exception
    {
        stickySessions = false;
        startBalancer(RelocationServlet.class);
        byte[] responseBytes = sendRequestToBalancer("/index.html");
        String msg = readFirstLine(responseBytes);
        Assert.assertEquals("success", msg);
    }

    private String readFirstLine(byte[] responseBytes) throws IOException
    {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(responseBytes)));
        return reader.readLine();
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
