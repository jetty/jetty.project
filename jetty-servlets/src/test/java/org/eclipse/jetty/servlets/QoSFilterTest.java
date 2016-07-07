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

package org.eclipse.jetty.servlets;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

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

import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletTester;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class QoSFilterTest
{
    private static final Logger LOG = Log.getLogger(QoSFilterTest.class);

    private ServletTester _tester;
    private LocalConnector[] _connectors;
    private final int NUM_CONNECTIONS = 8;
    private final int NUM_LOOPS = 6;
    private final int MAX_QOS = 4;

    @Before
    public void setUp() throws Exception
    {
        _tester = new ServletTester();
        _tester.setContextPath("/context");
        _tester.addServlet(TestServlet.class, "/test");
        TestServlet.__maxSleepers = 0;
        TestServlet.__sleepers = 0;

        _connectors = new LocalConnector[NUM_CONNECTIONS];
        for (int i = 0; i < _connectors.length; ++i)
            _connectors[i] = _tester.createLocalConnector();

        _tester.start();
    }

    @After
    public void tearDown() throws Exception
    {
        _tester.stop();
    }

    @Test
    public void testNoFilter() throws Exception
    {
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < NUM_CONNECTIONS; ++i)
        {
            workers.add(new Worker(i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(NUM_CONNECTIONS);
        List<Future<Void>> futures = executor.invokeAll(workers, 10, TimeUnit.SECONDS);

        rethrowExceptions(futures);

        if (TestServlet.__maxSleepers <= MAX_QOS)
            LOG.warn("TEST WAS NOT PARALLEL ENOUGH!");
        else
            assertThat(TestServlet.__maxSleepers, Matchers.lessThanOrEqualTo(NUM_CONNECTIONS));
    }

    @Test
    public void testBlockingQosFilter() throws Exception
    {
        FilterHolder holder = new FilterHolder(QoSFilter2.class);
        holder.setAsyncSupported(true);
        holder.setInitParameter(QoSFilter.MAX_REQUESTS_INIT_PARAM, "" + MAX_QOS);
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));

        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < NUM_CONNECTIONS; ++i)
        {
            workers.add(new Worker(i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(NUM_CONNECTIONS);
        List<Future<Void>> futures = executor.invokeAll(workers, 10, TimeUnit.SECONDS);

        rethrowExceptions(futures);

        if (TestServlet.__maxSleepers < MAX_QOS)
            LOG.warn("TEST WAS NOT PARALLEL ENOUGH!");
        else
            Assert.assertEquals(TestServlet.__maxSleepers, MAX_QOS);
    }

    @Test
    public void testQosFilter() throws Exception
    {
        FilterHolder holder = new FilterHolder(QoSFilter2.class);
        holder.setAsyncSupported(true);
        holder.setInitParameter(QoSFilter.MAX_REQUESTS_INIT_PARAM, String.valueOf(MAX_QOS));
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));

        List<Worker2> workers = new ArrayList<>();
        for (int i = 0; i < NUM_CONNECTIONS; ++i)
        {
            workers.add(new Worker2(i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(NUM_CONNECTIONS);
        List<Future<Void>> futures = executor.invokeAll(workers, 20, TimeUnit.SECONDS);

        rethrowExceptions(futures);

        if (TestServlet.__maxSleepers < MAX_QOS)
            LOG.warn("TEST WAS NOT PARALLEL ENOUGH!");
        else
            Assert.assertEquals(TestServlet.__maxSleepers, MAX_QOS);
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
        private int _num;

        public Worker(int num)
        {
            _num = num;
        }

        @Override
        public Void call() throws Exception
        {
            for (int i = 0; i < NUM_LOOPS; i++)
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

    class Worker2 implements Callable<Void>
    {
        private int _num;

        public Worker2(int num)
        {
            _num = num;
        }

        @Override
        public Void call() throws Exception
        {
            URL url = null;
            try
            {
                String addr = _tester.createConnector(true);
                for (int i = 0; i < NUM_LOOPS; i++)
                {
                    url = new URL(addr + "/context/test?priority=" + (_num % QoSFilter.__DEFAULT_MAX_PRIORITY) + "&n=" + _num + "&l=" + i);
                    url.getContent();
                }
            }
            catch (Exception e)
            {
                LOG.debug("Request " + url + " failed", e);
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
