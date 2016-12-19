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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionIdListener;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.Test;


/**
 * NullCacheRenewSessionTest
 *
 * Test that changes the session id during a request
 * on a SessionHandler that does not use session 
 * caching.
 */
public class NullCacheRenewSessionTest
{
    /**
     * MemorySessionDataStore
     *
     * Make a fake session data store that creates a new SessionData object
     * every time load(id) is called.
     */
    public static class MemorySessionDataStore extends AbstractSessionDataStore
    {
        public Map<String,SessionData> _map = new HashMap<>();


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
            return _map.containsKey(id);
        }

        /** 
         * @see org.eclipse.jetty.server.session.SessionDataMap#load(java.lang.String)
         */
        @Override
        public SessionData load(String id) throws Exception
        {
            SessionData sd = _map.get(id);
            if (sd == null)
                return null;
            SessionData nsd = new SessionData(id,"","",System.currentTimeMillis(),System.currentTimeMillis(), System.currentTimeMillis(),0 );
            nsd.copy(sd);
            return nsd;
        }

        /** 
         * @see org.eclipse.jetty.server.session.SessionDataMap#delete(java.lang.String)
         */
        @Override
        public boolean delete(String id) throws Exception
        {
            return (_map.remove(id) != null);
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doStore(java.lang.String, org.eclipse.jetty.server.session.SessionData, long)
         */
        @Override
        public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
        {
            _map.put(id,  data);
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doGetExpired(java.util.Set)
         */
        @Override
        public Set<String> doGetExpired(Set<String> candidates)
        {
            return Collections.emptySet();
        }

    }
    
    public static class NullCacheServer extends AbstractTestServer
    {

        /**
         * @param port
         * @param maxInactivePeriod
         * @param scavengePeriod
         * @param evictionPolicy
         * @throws Exception
         */
        public NullCacheServer(int port, int maxInactivePeriod, int scavengePeriod, int evictionPolicy) throws Exception
        {
            super(port, maxInactivePeriod, scavengePeriod, evictionPolicy);
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractTestServer#newSessionHandler()
         */
        @Override
        public SessionHandler newSessionHandler()
        {
            SessionHandler handler = new TestSessionHandler();
            SessionCache ss = new NullSessionCache(handler);
            handler.setSessionCache(ss);
            ss.setSessionDataStore(new MemorySessionDataStore());
            return handler;
        }
        
    }
    

    
    @Test
    /**
     * @throws Exception
     */
    public void testSessionRenewal() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int maxInactive = 1;
        int scavengePeriod = 3;
        AbstractTestServer server = new NullCacheServer (0, maxInactive, scavengePeriod, SessionCache.NEVER_EVICT);
       
        WebAppContext context = server.addWebAppContext(".", contextPath);
        context.setParentLoaderPriority(true);
        context.addServlet(TestServlet.class, servletMapping);
        TestHttpSessionIdListener testListener = new TestHttpSessionIdListener();
        context.addEventListener(testListener);
        


        HttpClient client = new HttpClient();
        try
        {
            server.start();
            int port=server.getPort();
            
            client.start();

            //make a request to create a session
            ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=create");
            assertEquals(HttpServletResponse.SC_OK,response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            assertFalse(testListener.isCalled());

            //make a request to change the sessionid
            Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=renew");
            request.header("Cookie", sessionCookie);
            ContentResponse renewResponse = request.send();

            assertEquals(HttpServletResponse.SC_OK,renewResponse.getStatus());
            String renewSessionCookie = renewResponse.getHeaders().get("Set-Cookie");
            assertNotNull(renewSessionCookie);
            assertNotSame(sessionCookie, renewSessionCookie);
            assertTrue(testListener.isCalled());
        }
        finally
        {
            client.stop();
            server.stop();
        }
    }

    
    
    public static class TestHttpSessionIdListener implements HttpSessionIdListener
    {
        boolean called = false;
        
        @Override
        public void sessionIdChanged(HttpSessionEvent event, String oldSessionId)
        {
            assertNotNull(event.getSession());
            assertNotSame(oldSessionId, event.getSession().getId());
            called = true;
        }
        
        public boolean isCalled()
        {
            return called;
        }
    }


    public static class TestServlet extends HttpServlet
    {
        
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("create".equals(action))
            {
                HttpSession session = request.getSession(true);
                assertTrue(session.isNew());
            }
            else if ("renew".equals(action))
            {
                HttpSession beforeSession = request.getSession(false);
                assertTrue(beforeSession != null);
                String beforeSessionId = beforeSession.getId();
          
                ((Session)beforeSession).renewId(request);

                HttpSession afterSession = request.getSession(false);

                assertTrue(afterSession != null);
                String afterSessionId = afterSession.getId();

                assertTrue(beforeSession==afterSession); //same object
                assertFalse(beforeSessionId.equals(afterSessionId)); //different id

                SessionHandler sessionManager = ((Session)afterSession).getSessionHandler();
                DefaultSessionIdManager sessionIdManager = (DefaultSessionIdManager)sessionManager.getSessionIdManager();

                assertTrue(sessionIdManager.isIdInUse(afterSessionId)); //new session id should be in use
                assertFalse(sessionIdManager.isIdInUse(beforeSessionId));

                HttpSession session = sessionManager.getSession(afterSessionId);
                assertNotNull(session);
                session = sessionManager.getSession(beforeSessionId);
                assertNull(session);

                if (((Session)afterSession).isIdChanged())
                {
                    ((org.eclipse.jetty.server.Response)response).addCookie(sessionManager.getSessionCookie(afterSession, request.getContextPath(), request.isSecure()));
                }
            }
        }
    }

}
