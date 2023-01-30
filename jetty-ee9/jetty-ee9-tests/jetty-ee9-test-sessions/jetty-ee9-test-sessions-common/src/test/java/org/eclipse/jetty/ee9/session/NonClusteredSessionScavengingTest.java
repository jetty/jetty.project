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

package org.eclipse.jetty.ee9.session;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionDataStore;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.eclipse.jetty.session.SessionManager;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * NonClusteredSessionScavengingTest
 *
 * Create a session, wait for it to be scavenged, re-present the cookie and check that  a
 * new session is created.
 */
public class NonClusteredSessionScavengingTest extends AbstractSessionTestBase
{
    public SessionDataStore _dataStore;

    public void pause(int scavenge) throws InterruptedException
    {
        Thread.sleep(TimeUnit.SECONDS.toMillis(scavenge));
    }

    @Test
    public void testNoScavenging() throws Exception
    {
        String contextPath = "/";
        String servletMapping = "/server";
        int inactivePeriod = 3;
        int scavengePeriod = 0; //turn off scavenging

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();

        SessionTestSupport server1 = new SessionTestSupport(0, inactivePeriod, scavengePeriod,
            cacheFactory, storeFactory);
        ServletContextHandler context1 = server1.addContext(contextPath);
        context1.addServlet(TestServlet.class, servletMapping);
        TestHttpSessionListener listener = new TestHttpSessionListener();
        context1.getSessionHandler().addEventListener(listener);

        try
        {
            server1.start();
            int port1 = server1.getPort();

            HttpClient client = new HttpClient();
            client.start();
            try
            {
                String url = "http://localhost:" + port1 + contextPath + servletMapping.substring(1);

                // Create the session
                ContentResponse response1 = client.GET(url + "?action=create");
                assertEquals(HttpServletResponse.SC_OK, response1.getStatus());
                String sessionCookie = response1.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                //test session was created
                SessionManager m1 = context1.getSessionHandler().getSessionManager();
                assertEquals(1, m1.getSessionsCreated());
                assertThat(listener.createdSessions, Matchers.contains(SessionTestSupport.extractSessionId(sessionCookie)));

                // Wait a while to ensure that the session should have expired, if the
                //scavenger was running
                pause(2 * inactivePeriod);

                //check the session listener did not get called
                assertTrue(listener.destroyedSessions.isEmpty());
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            server1.stop();
        }
    }

    @Test
    public void testNewSession() throws Exception
    {
        String servletMapping = "/server";
        int scavengePeriod = 3;
        int maxInactivePeriod = 1;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(scavengePeriod);

        SessionTestSupport server = new SessionTestSupport(0, maxInactivePeriod, scavengePeriod,
            cacheFactory, storeFactory);
        ServletContextHandler context = server.addContext("/");
        _dataStore = context.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore();

        context.addServlet(TestServlet.class, servletMapping);
        String contextPath = "/";

        try
        {
            server.start();
            int port = server.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping.substring(1) + "?action=create");
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                // Let's wait for the scavenger to run
                pause(maxInactivePeriod + scavengePeriod);

                assertFalse(_dataStore.exists(SessionTestSupport.extractSessionId(sessionCookie)));

                // The session should not be there anymore, but we present an old cookie
                // The server should create a new session.
                Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping.substring(1) + "?action=old-create");
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie2 = response.getHeaders().get("Set-Cookie");
                assertNotEquals(SessionTestSupport.extractSessionId(sessionCookie), SessionTestSupport.extractSessionId(sessionCookie2));
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

    @Test
    public void testImmortalSession() throws Exception
    {
        String servletMapping = "/server";
        int scavengePeriod = 1;
        int maxInactivePeriod = 0;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(scavengePeriod);

        SessionTestSupport server = new SessionTestSupport(0, maxInactivePeriod, scavengePeriod,
            cacheFactory, storeFactory);
        ServletContextHandler context = server.addContext("/");
        _dataStore = context.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore();
        context.addServlet(TestServlet.class, servletMapping);
        String contextPath = "/";

        try
        {
            server.start();
            int port = server.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                //create an immortal session
                ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping.substring(1) + "?action=create");
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                // Let's wait for the scavenger to run
                pause(2 * scavengePeriod);

                assertTrue(_dataStore.exists(SessionTestSupport.extractSessionId(sessionCookie)));

                // Test that the session is still there
                Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping.substring(1) + "?action=old-test");
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
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
            }
            else if ("old-create".equals(action))
            {
                HttpSession s = request.getSession(false);
                assertNull(s);
                s = request.getSession(true);
                assertNotNull(s);
                assertFalse(s.getId().equals(id));
            }
            else if ("old-test".equals(action))
            {
                HttpSession s = request.getSession(false);
                assertNotNull(s);
                assertTrue(s.getId().equals(id));
            }
            else
            {
                fail("Unknown servlet action");
            }
        }
    }

    /**
     *
     */
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return new TestSessionDataStoreFactory();
    }
}
