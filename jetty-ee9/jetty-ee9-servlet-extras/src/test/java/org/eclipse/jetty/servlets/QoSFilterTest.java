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

package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class QoSFilterTest
{
    private static final Logger LOG = LoggerFactory.getLogger(QoSFilterTest.class);

    private Server server;
    private LocalConnector[] _connectors;
    private ServletContextHandler context;
    private final int numConnections = 8;
    private final int numLoops = 6;
    private final int maxQos = 4;

    @BeforeEach
    public void setUp() throws Exception
    {
        server = new Server();
        context = new ServletContextHandler(server, "/context");
        context.addServlet(TestServlet.class, "/test");
        TestServlet.__maxSleepers = 0;
        TestServlet.__sleepers = 0;

        _connectors = new LocalConnector[numConnections];
        for (int i = 0; i < _connectors.length; ++i)
        {
            _connectors[i] = new LocalConnector(server);
            server.addConnector(_connectors[i]);
        }

        server.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testNoFilter() throws Exception
    {
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < numConnections; ++i)
        {
            workers.add(new Worker(i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(numConnections);
        List<Future<Void>> futures = executor.invokeAll(workers, 10, TimeUnit.SECONDS);

        rethrowExceptions(futures);

        assertThat(TestServlet.__maxSleepers, Matchers.lessThanOrEqualTo(numConnections));
    }

    @Test
    public void testBlockingQosFilter() throws Exception
    {
        FilterHolder holder = new FilterHolder(QoSFilter2.class);
        holder.setAsyncSupported(true);
        holder.setInitParameter(QoSFilter.MAX_REQUESTS_INIT_PARAM, "" + maxQos);
        context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));

        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < numConnections; ++i)
        {
            workers.add(new Worker(i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(numConnections);
        List<Future<Void>> futures = executor.invokeAll(workers, 10, TimeUnit.SECONDS);

        rethrowExceptions(futures);

        assertEquals(TestServlet.__maxSleepers, maxQos);
    }

    @Test
    public void testQosFilter() throws Exception
    {
        FilterHolder holder = new FilterHolder(QoSFilter2.class);
        holder.setAsyncSupported(true);
        holder.setInitParameter(QoSFilter.MAX_REQUESTS_INIT_PARAM, String.valueOf(maxQos));
        context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));

        List<Worker2> workers = new ArrayList<>();
        for (int i = 0; i < numConnections; ++i)
        {
            workers.add(new Worker2(i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(numConnections);
        List<Future<Void>> futures = executor.invokeAll(workers, 20, TimeUnit.SECONDS);

        rethrowExceptions(futures);

        assertEquals(TestServlet.__maxSleepers, maxQos);
    }

    private void rethrowExceptions(List<Future<Void>> futures) throws Exception
    {
        for (Future<Void> future : futures)
        {
            future.get();
        }
    }

    class Worker implements Callable<Void>
    {
        private final int _num;

        public Worker(int num)
        {
            _num = num;
        }

        @Override
        public Void call() throws Exception
        {
            for (int i = 0; i < numLoops; i++)
            {
                HttpTester.Request request = HttpTester.newRequest();

                request.setMethod("GET");
                request.setHeader("host", "tester");
                request.setURI("/context/test?priority=" + (_num % QoSFilter.__DEFAULT_MAX_PRIORITY));
                request.setHeader("num", _num + "");

                String responseString = _connectors[_num].getResponse(BufferUtil.toString(request.generate()));

                assertThat("Response contains", responseString, containsString("HTTP"));
            }

            return null;
        }
    }

    private class Worker2 implements Callable<Void>
    {
        private final int _num;

        private Worker2(int num)
        {
            _num = num;
        }

        @Override
        public Void call() throws Exception
        {
            URL url = null;
            ServerConnector connector = null;
            try
            {
                connector = new ServerConnector(server);
                server.addConnector(connector);
                connector.start();

                String addr = "http://localhost:" + connector.getLocalPort();
                for (int i = 0; i < numLoops; i++)
                {
                    url = new URL(addr + "/context/test?priority=" + (_num % QoSFilter.__DEFAULT_MAX_PRIORITY) + "&n=" + _num + "&l=" + i);
                    url.getContent();
                }
            }
            catch (Exception e)
            {
                LOG.debug("Request " + url + " failed", e);
            }
            finally
            {
                if (connector != null)
                {
                    connector.stop();
                    server.removeConnector(connector);
                }
            }
            return null;
        }
    }

    public static class TestServlet extends HttpServlet
    {
        private static int __sleepers;
        private static int __maxSleepers;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            try
            {
                synchronized (TestServlet.class)
                {
                    __sleepers++;
                    if (__sleepers > __maxSleepers)
                        __maxSleepers = __sleepers;
                }

                Thread.sleep(50);

                synchronized (TestServlet.class)
                {
                    __sleepers--;
                }

                response.setContentType("text/plain");
                response.getWriter().println("DONE!");
            }
            catch (InterruptedException e)
            {
                response.sendError(500);
            }
        }
    }

    public static class QoSFilter2 extends QoSFilter
    {
        @Override
        public int getPriority(ServletRequest request)
        {
            String p = request.getParameter("priority");
            if (p != null)
                return Integer.parseInt(p);
            return 0;
        }
    }
}
