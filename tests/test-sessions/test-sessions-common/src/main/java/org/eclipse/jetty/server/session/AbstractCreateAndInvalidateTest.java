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


package org.eclipse.jetty.server.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.server.session.SessionCache;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;



/**
 * AbstractCreateAndInvalidateTest
 *
 * Test that creating a session and invalidating it before the request exits the session
 * does not result in the session being persisted
 */
public abstract class AbstractCreateAndInvalidateTest extends AbstractTestBase
{

    protected TestServlet _servlet = new TestServlet();
    protected AbstractTestServer _server1 = null;
    
    
    /**
     * @param sessionId
     * @param isPersisted
     */
    public abstract void checkSession (String sessionId, boolean isPersisted) throws Exception;
    
    /**
     * @param sessionid
     * @param contextId
     * @param isPersisted
     * @throws Exception
     */
    public abstract void checkSessionByKey (String sessionid, String contextId, boolean isPersisted) throws Exception;
    
    
    /**
     * Create and then invalidate a session in the same request.
     * @throws Exception
     */
    @Test
    public void testSessionCreateAndInvalidate() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 20;
        int scavengePeriod = 3;
     


        _server1 = createServer(0, inactivePeriod, scavengePeriod, SessionCache.NEVER_EVICT);
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = _server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        _server1.start();
        int port1 = _server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK,response.getStatus());
            
            //check that the session does not exist
            checkSession(_servlet._id, false);

            
        }
        finally
        {
            _server1.stop();
        }
    }
    
    /**
     * Create a session in a context, forward to another context and create a 
     * session in it too. Check that both sessions exist after the response
     * completes.
     * @throws Exception
     */
    @Test
    public void testSessionCreateForward () throws Exception
    {
        String contextPath = "";
        String contextB = "/contextB";
        String servletMapping = "/server";
        int inactivePeriod = 20;
        int scavengePeriod = 3;
     


        _server1 = createServer(0, inactivePeriod, scavengePeriod, SessionCache.NEVER_EVICT);
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = _server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        ServletContextHandler ctxB = _server1.addContext(contextB);
        ctxB.addServlet(TestServletB.class, servletMapping);
        _server1.start();
        int port1 = _server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url+"?action=forward");
            assertEquals(HttpServletResponse.SC_OK,response.getStatus());
  
            //check that the sessions exist persisted
            checkSession(_servlet._id, true);
            checkSessionByKey (_servlet._id, "0_0_0_0:", true);
            checkSessionByKey (_servlet._id, "0_0_0_0:_contextB", true);
        }
        finally
        {
            _server1.stop();
        }
    }
    
    /**
     * 
     * Create a session in one context, forward to another context and create another session
     * in it, then invalidate the session in the original context: that should invalidate the
     * session in both contexts and no session should exist after the response completes.
     * @throws Exception
     */
    @Test
    public void testSessionCreateForwardAndInvalidate () throws Exception 
    {
        String contextPath = "";
        String contextB = "/contextB";
        String servletMapping = "/server";
        int inactivePeriod = 20;
        int scavengePeriod = 3;
     


        _server1 = createServer(0, inactivePeriod, scavengePeriod, SessionCache.NEVER_EVICT);
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = _server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        ServletContextHandler ctxB = _server1.addContext(contextB);
        ctxB.addServlet(TestServletB.class, servletMapping);
        _server1.start();
        int port1 = _server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url+"?action=forwardinv");
            assertEquals(HttpServletResponse.SC_OK,response.getStatus());
    

            //check that the session does not exist 
            checkSession(_servlet._id, false);           
        }
        finally
        {
            _server1.stop();
        }
    }




    public static class TestServlet extends HttpServlet
    {
        public String _id = null;


        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");

            if (action != null && action.startsWith("forward"))
            {
                HttpSession session = request.getSession(true);
                _id = session.getId();
                session.setAttribute("value", new Integer(1));

                ServletContext contextB = getServletContext().getContext("/contextB");
                RequestDispatcher dispatcherB = contextB.getRequestDispatcher(request.getServletPath());
                dispatcherB.forward(request, httpServletResponse);

                if (action.endsWith("inv"))
                    session.invalidate();

                return;
            }
            
            HttpSession session = request.getSession(true);
            _id = session.getId();
            session.setAttribute("value", new Integer(1));
            session.invalidate();
        }
    }

    public static class TestServletB extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            HttpSession session = request.getSession(false);
            if (session == null) session = request.getSession(true);

            // Be sure nothing from contextA is present
            Object objectA = session.getAttribute("A");
            assertTrue(objectA == null);

            // Add something, so in contextA we can check if it is visible (it must not).
            session.setAttribute("B", "B");
        }
    }
}
