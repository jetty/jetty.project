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





import java.io.IOException;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Test;

/**
 * AbstractSessionEvictionFailureTest
 *
 *
 */
public abstract class AbstractSessionEvictionFailureTest extends AbstractTestBase
{

    /**
     * TestSessionDataStore
     *
     *
     */
    public static class TestSessionDataStore extends AbstractSessionDataStore
    {
        public boolean _nextResult;
        
        public void setNextResult (boolean goodOrBad)
        {
            _nextResult = goodOrBad;
        }
        
        /** 
         * @see org.eclipse.jetty.server.session.SessionDataStore#isPassivating()
         */
        @Override
        public boolean isPassivating()
        {
            return false;
        }

        /** 
         * @see org.eclipse.jetty.server.session.SessionDataStore#exists(java.lang.String)
         */
        @Override
        public boolean exists(String id) throws Exception
        {
            return true;
        }

        /** 
         * @see org.eclipse.jetty.server.session.SessionDataMap#load(java.lang.String)
         */
        @Override
        public SessionData load(String id) throws Exception
        {
            return null;
        }

        /** 
         * @see org.eclipse.jetty.server.session.SessionDataMap#delete(java.lang.String)
         */
        @Override
        public boolean delete(String id) throws Exception
        {
            return false;
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doStore(java.lang.String, org.eclipse.jetty.server.session.SessionData, long)
         */
        @Override
        public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
        {
            if (!_nextResult)
            {
                System.err.println("Throwing exception on store!");

                throw new IllegalStateException("Testing store");
            }
            else
                System.err.println("Storing");

        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doGetExpired(java.util.Set)
         */
        @Override
        public Set<String> doGetExpired(Set<String> candidates)
        {
            return candidates;
        }
    }
    
    
    
    /**
     * TestServlet
     *
     *
     */
    public static class TestServlet extends HttpServlet
    {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("aaa", new Integer(0));
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
               assertNotNull(session);
               Integer count = (Integer)session.getAttribute("aaa");
               assertNotNull(count);
               session.setAttribute("aaa", new Integer(count.intValue()+1));
            }
        }
    }
    
    
    /**
     * @param sec
     */
    public static void pause (int sec)
    {
        try
        {
            Thread.currentThread().sleep(sec*1000L);
        }
        catch (InterruptedException e)
        {
            //just return;
        }
    }
  
    
    @Test
    public void testEvictFailure () throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 0;
        int scavengePeriod = 20;
        int evictionPeriod = 5; //evict after  inactivity
        AbstractTestServer server = createServer(0, inactivePeriod, scavengePeriod, evictionPeriod);
        ServletContextHandler context = server.addContext(contextPath);
        context.getSessionHandler().getSessionCache().setSaveOnInactiveEviction(true);
        TestSessionDataStore ds = new TestSessionDataStore();
        context.getSessionHandler().getSessionCache().setSessionDataStore(ds);
        
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        context.addServlet(holder, servletMapping);
    
        try
        {
            server.start();
            int port1 = server.getPort();          
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                String url = "http://localhost:" + port1 + contextPath + servletMapping;

                // Create the session
                ds.setNextResult(true);
                ContentResponse response = client.GET(url + "?action=init");
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertNotNull(sessionCookie);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
                
                ds.setNextResult(false);
                
                //Wait for the eviction period to expire - save on evict should fail but session
                //should remain in the cache
                pause(evictionPeriod);
                
                ds.setNextResult(true);
                
                // Make another request to see if the session is still in the cache and can be used,
                //allow it to be saved this time
                Request request = client.newRequest(url + "?action=test");
                request.header("Cookie", sessionCookie);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                assertNull(response.getHeaders().get("Set-Cookie")); //check that the cookie wasn't reset
                ds.setNextResult(false);
                
                System.err.println("Waiting again for session to expire");

                //Wait for the eviction period to expire again
                pause(evictionPeriod);
                
                ds.setNextResult(true);
                
                request = client.newRequest(url + "?action=test");
                request.header("Cookie", sessionCookie);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            server.stop();
        }
    }
    
}
