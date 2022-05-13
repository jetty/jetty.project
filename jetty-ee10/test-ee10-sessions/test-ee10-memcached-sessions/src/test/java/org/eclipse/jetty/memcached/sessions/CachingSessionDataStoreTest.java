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

package org.eclipse.jetty.memcached.sessions;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.session.SessionTestSupport;
import org.eclipse.jetty.memcached.sessions.MemcachedTestHelper.MockDataStore;
import org.eclipse.jetty.session.CachingSessionDataStore;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionDataMap;
import org.eclipse.jetty.session.SessionDataStore;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CachingSessionDataStoreTest
 */
@Testcontainers(disabledWithoutDocker = true)
public class CachingSessionDataStoreTest
{
    @Test
    public void testSessionCRUD() throws Exception
    {
        String servletMapping = "/server";
        int scavengePeriod = -1;
        int maxInactivePeriod = -1;
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setFlushOnResponseCommit(true); //ensure changes are saved before response commits so we can check values after response
        SessionDataStoreFactory storeFactory = MemcachedTestHelper.newSessionDataStoreFactory();

        //Make sure sessions are evicted on request exit so they will need to be reloaded via cache/persistent store
        SessionTestSupport server = new SessionTestSupport(0, maxInactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        ServletContextHandler context = server.addContext("/");
        context.addServlet(TestServlet.class, servletMapping);
        String contextPath = "";

        try
        {
            server.start();
            int port = server.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                //
                //Create a session
                //
                ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=create");
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                String id = SessionTestSupport.extractSessionId(sessionCookie);

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
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                assertEquals(0, ((MockDataStore)persistentStore).getLoadCount());

                //check it was updated in the persistent store
                SessionData sd = persistentStore.load(id);
                assertNotNull(sd);
                assertEquals("bar", sd.getAttribute("foo"));

                //check it was updated in the cache
                sd = dataMap.load(id);
                assertNotNull(sd);
                assertEquals("bar", sd.getAttribute("foo"));
                
                //invalidate a session and check its gone from cache and store
                request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=del");
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                assertNull(persistentStore.load(id));
                assertNull(dataMap.load(id));
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
            if ("del".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                session.invalidate();
                return;
            }
        }
    }

    @AfterAll
    public static void shutdown() throws Exception
    {
        MemcachedTestHelper.shutdown();
    }
}
