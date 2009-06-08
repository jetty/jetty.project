// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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
package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.testing.HttpTester;
import org.eclipse.jetty.testing.ServletTester;
import org.eclipse.jetty.util.log.Log;

public class QoSFilterTest extends TestCase 
{
    private ServletTester _tester;
    private LocalConnector[] _connectors;
    private CountDownLatch _doneRequests;
    private final int NUM_CONNECTIONS = 8;
    private final int NUM_LOOPS = 6;
    private final int MAX_QOS = 4;
    
    protected void setUp() throws Exception 
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
        
    protected void tearDown() throws Exception 
    {
        _tester.stop();
    }

    public void testNoFilter() throws Exception
    {    
        for(int i = 0; i < NUM_CONNECTIONS; ++i )
        {
            new Thread(new Worker(i)).start();
        }
        
        _doneRequests.await(10,TimeUnit.SECONDS);
        
        assertFalse("TEST WAS NOT PARALLEL ENOUGH!",TestServlet.__maxSleepers<=MAX_QOS);
        assertTrue(TestServlet.__maxSleepers<=NUM_CONNECTIONS);
    }

    public void testBlockingQosFilter() throws Exception
    {
        FilterHolder holder = new FilterHolder(QoSFilter2.class);
        holder.setAsyncSupported(true);
        holder.setInitParameter(QoSFilter.MAX_REQUESTS_INIT_PARAM, ""+MAX_QOS);
        _tester.getContext().getServletHandler().addFilterWithMapping(holder,"/*",FilterMapping.DEFAULT);

        for(int i = 0; i < NUM_CONNECTIONS; ++i )
        {
            new Thread(new Worker(i)).start();
        }

        _doneRequests.await(10,TimeUnit.SECONDS);
        assertFalse("TEST WAS NOT PARALLEL ENOUGH!",TestServlet.__maxSleepers<MAX_QOS);
        assertTrue(TestServlet.__maxSleepers==MAX_QOS);
    }

    public void testQosFilter() throws Exception
    {    
        FilterHolder holder = new FilterHolder(QoSFilter2.class);
        holder.setAsyncSupported(true);
        holder.setInitParameter(QoSFilter.MAX_REQUESTS_INIT_PARAM, ""+MAX_QOS);
        _tester.getContext().getServletHandler().addFilterWithMapping(holder,"/*",FilterMapping.DEFAULT);
        
        for(int i = 0; i < NUM_CONNECTIONS; ++i )
        {
            new Thread(new Worker2(i)).start();
        }
        
        _doneRequests.await(20,TimeUnit.SECONDS);
        assertFalse("TEST WAS NOT PARALLEL ENOUGH!",TestServlet.__maxSleepers<MAX_QOS);
        assertTrue(TestServlet.__maxSleepers<=MAX_QOS);
    }
    
    class Worker implements Runnable {
        private int _num;
        public Worker(int num)
        {
            _num = num;
        }

        public void run()
        {
            for (int i=0;i<NUM_LOOPS;i++)
            {
                HttpTester request = new HttpTester();
                HttpTester response = new HttpTester();

                request.setMethod("GET");
                request.setHeader("host", "tester");
                request.setURI("/context/test?priority="+(_num%QoSFilter.__DEFAULT_MAX_PRIORITY));
                request.setHeader("num", _num+"");
                try
                {
                    String responseString = _tester.getResponses(request.generate(), _connectors[_num]);
                    int index=-1;
                    if((index = responseString.indexOf("HTTP", index+1))!=-1)
                    {
                        responseString = response.parse(responseString);
                        _doneRequests.countDown();
                    }
                }
                catch (IOException e)
                {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                catch (Exception e)
                {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
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

        public void run()
        {
            URL url=null;
            try
            {
                String addr = _tester.createSocketConnector(true);
                for (int i=0;i<NUM_LOOPS;i++)
                {
                    url=new URL(addr+"/context/test?priority="+(_num%QoSFilter.__DEFAULT_MAX_PRIORITY)+"&n="+_num+"&l="+i);
                    // System.err.println(_num+"-"+i+" Try "+url);
                    InputStream in = (InputStream)url.getContent();
                    _doneRequests.countDown();
                    // System.err.println(_num+"-"+i+" Got "+IO.toString(in)+" "+_doneRequests.getCount());
                }
            }
            catch(Exception e)
            {
                Log.warn(url.toString());
                Log.debug(e);
            }
        }
    }
    
    public static class TestServlet extends HttpServlet implements Servlet
    {
        private int _count;
        private static int __sleepers;
        private static int __maxSleepers;
         
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
        public int getPriority(ServletRequest request)
        {
            String p = ((HttpServletRequest)request).getParameter("priority");
            if (p!=null)
                return Integer.parseInt(p);
            return 0;
        }
    }
    
}
