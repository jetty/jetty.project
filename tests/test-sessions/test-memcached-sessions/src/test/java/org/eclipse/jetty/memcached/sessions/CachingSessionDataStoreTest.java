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


package org.eclipse.jetty.memcached.sessions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.memcached.sessions.MemcachedTestServer.MockDataStore;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.eclipse.jetty.server.session.CachingSessionDataStore;
import org.eclipse.jetty.server.session.SessionCache;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataMap;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Test;
/**
 * CachingSessionDataStoreTest
 *
 *
 */
public class CachingSessionDataStoreTest
{
    public AbstractTestServer createServer (int port, int max, int scavenge,int evictionPolicy) throws Exception
    {
       return new MemcachedTestServer(port, max, scavenge, evictionPolicy);
    }
    
    @Test
    public void testSessionCRUD () throws Exception
    {
        String servletMapping = "/server";
        int scavengePeriod = -1;
        int maxInactivePeriod = -1;
        //Make sure sessions are evicted on request exit so they will need to be reloaded via cache/persistent store
        AbstractTestServer server = createServer(0, maxInactivePeriod, scavengePeriod, SessionCache.EVICT_ON_SESSION_EXIT);
        ServletContextHandler context = server.addContext("/");
        context.addServlet(TestServlet.class, servletMapping);
        String contextPath = "";

        try
        {
            server.start();
            int port=server.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                //
                //Create a session
                //
                ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=create");
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
                String id = AbstractTestServer.extractSessionId(sessionCookie);
                
                //check that the memcache contains the session, and the session data store contains the session
                CachingSessionDataStore ds = (CachingSessionDataStore)context.getSessionHandler().getSessionCache().getSessionDataStore();
                assertNotNull(ds);
                SessionDataStore persistentStore = ds.getSessionStore();
                SessionDataMap dataMap = ds.getSessionDataMap();
                //the backing persistent store contains the session
                assertNotNull(persistentStore.load(id));  
                //the memcache cache contains the session
                assertNotNull(dataMap.load(id));
                
                //
                //Update a session and check that is is NOT loaded via the persistent store
                //
                ((MockDataStore)persistentStore).zeroLoadCount();
                Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=update");
                request.header("Cookie", sessionCookie);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                assertEquals(0, ((MockDataStore)persistentStore).getLoadCount());
                
                
                //check it was updated in the persistent store
                SessionData sd = persistentStore.load(id);
                assertNotNull(sd);
                assertEquals("bar", sd.getAttribute("foo"));
                
                //check it was updated in the cache
                sd = dataMap.load(id);
                assertNotNull(sd);
                assertEquals("bar", sd.getAttribute("foo"));
                
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
    
    public static class TestServlet extends HttpServlet
    {
        String id;
        
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("create".equals(action))
            {
                HttpSession session = request.getSession(true);
                assertTrue(session.isNew());
                id = session.getId();
                return;
            }
            if ("update".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                session.setAttribute("foo", "bar");
                return;
            }
        }
    }
    
}
