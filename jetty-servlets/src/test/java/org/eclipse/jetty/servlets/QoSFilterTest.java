//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.DispatcherType;
import javax.servlet.Servlet;
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
    private CountDownLatch _doneRequests;
    private final int NUM_CONNECTIONS = 8;
    private final int NUM_LOOPS = 6;
    private final int MAX_QOS = 4;

    @Before
    public void setUp() throws Exception
    {
        _tester = new ServletTester();
        _tester.setContextPath("/context");
        _tester.addServlet(TestServlet.class, "/test");
        TestServlet.__maxSleepers=0;
        TestServlet.__sleepers=0;

        _connectors = new LocalConnector[NUM_CONNECTIONS];
        for(int i = 0; i < _connectors.length; ++i)
            _connectors[i] = _tester.createLocalConnector();

        _doneRequests = new CountDownLatch(NUM_CONNECTIONS*NUM_LOOPS);

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
        for(int i = 0; i < NUM_CONNECTIONS; ++i )
        {
            new Thread(new Worker(i)).start();
        }

        _doneRequests.await(10,TimeUnit.SECONDS);

        if (TestServlet.__maxSleepers<=MAX_QOS)
            LOG.warn("TEST WAS NOT PARALLEL ENOUGH!");
        else
            Assert.assertThat(TestServlet.__maxSleepers,Matchers.lessThanOrEqualTo(NUM_CONNECTIONS));
    }

    @Test
    public void testBlockingQosFilter() throws Exception
    {
        FilterHolder holder = new FilterHolder(QoSFilter2.class);
        holder.setAsyncSupported(true);
        holder.setInitParameter(QoSFilter.MAX_REQUESTS_INIT_PARAM, ""+MAX_QOS);
        _tester.getContext().getServletHandler().addFilterWithMapping(holder,"/*",EnumSet.of(DispatcherType.REQUEST,DispatcherType.ASYNC));

        for(int i = 0; i < NUM_CONNECTIONS; ++i )
        {
            new Thread(new Worker(i)).start();
        }

        _doneRequests.await(10,TimeUnit.SECONDS);
        if (TestServlet.__maxSleepers<MAX_QOS)
            LOG.warn("TEST WAS NOT PARALLEL ENOUGH!");
        else
            Assert.assertEquals(TestServlet.__maxSleepers,MAX_QOS);
    }

    @Test
    public void testQosFilter() throws Exception
    {
        FilterHolder holder = new FilterHolder(QoSFilter2.class);
        holder.setAsyncSupported(true);
        holder.setInitParameter(QoSFilter.MAX_REQUESTS_INIT_PARAM, ""+MAX_QOS);
        _tester.getContext().getServletHandler().addFilterWithMapping(holder,"/*",EnumSet.of(DispatcherType.REQUEST,DispatcherType.ASYNC));
        for(int i = 0; i < NUM_CONNECTIONS; ++i )
        {
            new Thread(new Worker2(i)).start();
        }

        _doneRequests.await(20,TimeUnit.SECONDS);
        if (TestServlet.__maxSleepers<MAX_QOS)
            LOG.warn("TEST WAS NOT PARALLEL ENOUGH!");
        else
            Assert.assertEquals(TestServlet.__maxSleepers,MAX_QOS);
    }

    class Worker implements Runnable {
        private int _num;
        public Worker(int num)
        {
            _num = num;
        }

        @Override
        public void run()
        {
            for (int i=0;i<NUM_LOOPS;i++)
            {
                HttpTester.Request request = HttpTester.newRequest();

                request.setMethod("GET");
                request.setHeader("host", "tester");
                request.setURI("/context/test?priority="+(_num%QoSFilter.__DEFAULT_MAX_PRIORITY));
                request.setHeader("num", _num+"");
                try
                {
                    String responseString = _connectors[_num].getResponses(BufferUtil.toString(request.generate()));
                    if(responseString.indexOf("HTTP")!=-1)
                    {
                        _doneRequests.countDown();
                    }
                }
                catch (Exception x)
                {
                    assertTrue(false);
                }
            }
        }
    }

    class Worker2 implements Runnable {
        private int _num;
        public Worker2(int num)
        {
            _num = num;
        }

        @Override
        public void run()
        {
            URL url=null;
            try
            {
                String addr = _tester.createConnector(true);
                for (int i=0;i<NUM_LOOPS;i++)
                {
                    url=new URL(addr+"/context/test?priority="+(_num%QoSFilter.__DEFAULT_MAX_PRIORITY)+"&n="+_num+"&l="+i);
                    // System.err.println(_num+"-"+i+" Try "+url);
                    url.getContent();
                    _doneRequests.countDown();
                    // System.err.println(_num+"-"+i+" Got "+IO.toString(in)+" "+_doneRequests.getCount());
                }
            }
            catch(Exception e)
            {
                LOG.warn(String.valueOf(url));
                LOG.debug(e);
            }
        }
    }

    public static class TestServlet extends HttpServlet implements Servlet
    {
        private static int __sleepers;
        private static int __maxSleepers;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            try
            {
                synchronized(TestServlet.class)
                {
                    __sleepers++;
                    if(__sleepers > __maxSleepers)
                        __maxSleepers = __sleepers;
                }

                Thread.sleep(50);

                synchronized(TestServlet.class)
                {
                    // System.err.println(_count++);
                    __sleepers--;
                    if(__sleepers > __maxSleepers)
                        __maxSleepers = __sleepers;
                }

                response.setContentType("text/plain");
                response.getWriter().println("DONE!");
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
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
            if (p!=null)
                return Integer.parseInt(p);
            return 0;
        }
    }
}
