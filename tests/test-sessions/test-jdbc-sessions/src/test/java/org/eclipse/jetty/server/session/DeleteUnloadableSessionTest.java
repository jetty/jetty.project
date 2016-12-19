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
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.util.Set;

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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.Test;


/**
 * DeleteUnloadableSessionTest
 *
 *
 */
public class DeleteUnloadableSessionTest
{
    
    /**
     * TestSessionDataStore
     *
     *
     */
    public static class TestSessionDataStore extends AbstractSessionDataStore
    {
        int count = 0;
        
        /** 
         * @see org.eclipse.jetty.server.session.SessionDataStore#isPassivating()
         */
        @Override
        public boolean isPassivating()
        {
            return true;
        }

        /** 
         * @see org.eclipse.jetty.server.session.SessionDataStore#exists(java.lang.String)
         */
        @Override
        public boolean exists(String id) throws Exception
        {
            return false;
        }

        /** 
         * @see org.eclipse.jetty.server.session.SessionDataMap#load(java.lang.String)
         */
        @Override
        public SessionData load(String id) throws Exception
        {
            ++count;
            if (count == 1)
                throw new UnreadableSessionDataException(id, _context, new IllegalStateException());
            
            return new SessionData(id, "", "", System.currentTimeMillis(), System.currentTimeMillis(), System.currentTimeMillis(), -1);
        }

        /** 
         * @see org.eclipse.jetty.server.session.SessionDataMap#delete(java.lang.String)
         */
        @Override
        public boolean delete(String id) throws Exception
        {
            return true;
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doStore(java.lang.String, org.eclipse.jetty.server.session.SessionData, long)
         */
        @Override
        public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
        {
            //pretend it was saved
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doGetExpired(java.util.Set)
         */
        @Override
        public Set<String> doGetExpired(Set<String> candidates)
        {
            return null;
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

            if ("test".equals(action))
            {
               HttpSession session = request.getSession(false);
               assertNull(session);
            }
        }
    }
    
    
    /**
     * Test that session data that can't be loaded results in a null Session object
     * @throws Exception
     */
    @Test
    public void testDeleteUnloadableSession () throws Exception
    {        
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = -1;
        int scavengePeriod = 100;
        JdbcTestServer server = new JdbcTestServer(0, inactivePeriod, scavengePeriod, SessionCache.NEVER_EVICT);
        ServletContextHandler context = server.addContext(contextPath);
        context.getSessionHandler().getSessionCache().setRemoveUnloadableSessions(true);
  
        TestSessionDataStore ds = new TestSessionDataStore();
        context.getSessionHandler().getSessionCache().setSessionDataStore(ds);
        
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        context.addServlet(holder, servletMapping);
    
        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            server.start();
            int port = server.getPort();          
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                String sessionCookie = "JSESSIONID=w0rm3zxpa6h1zg1mevtv76b3te00.w0;$Path=/";
                Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping+ "?action=test");
                request.header("Cookie", sessionCookie);
                ContentResponse response = request.send();
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
