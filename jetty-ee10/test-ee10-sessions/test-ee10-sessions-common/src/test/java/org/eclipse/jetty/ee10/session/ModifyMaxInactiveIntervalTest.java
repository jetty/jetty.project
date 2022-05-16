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

package org.eclipse.jetty.ee10.session;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModifyMaxInactiveIntervalTest
 */
public class ModifyMaxInactiveIntervalTest extends AbstractSessionTestBase
{
    public static int __scavenge = 1;

    /**
     * Test that setting an integer overflow valued max inactive interval
     * results in an immortal session (value -1).
     */
    @Test
    public void testHugeMaxInactiveInterval() throws Exception
    {
        int inactivePeriod = Integer.MAX_VALUE * 60; //integer overflow
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(SessionTestSupport.DEFAULT_SCAVENGE_SEC);

        SessionTestSupport server = new SessionTestSupport(0, inactivePeriod, __scavenge, cacheFactory, storeFactory);
        ServletContextHandler ctxA = server.addContext("/mod");
        ctxA.addServlet(TestModServlet.class, "/test");

        server.start();
        int port = server.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // Perform a request to create a session
                ContentResponse response = client.GET("http://localhost:" + port + "/mod/test?action=create");

                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                String id = SessionTestSupport.extractSessionId(sessionCookie);

                //check that the maxInactive is -1
                Session s = ctxA.getSessionHandler().getSession(id);
                assertEquals(-1, s.getMaxInactiveInterval());
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
    public void testReduceMaxInactiveInterval() throws Exception
    {
        int oldMaxInactive = 30;
        int newMaxInactive = 1;
        int scavengeSec = __scavenge;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(SessionTestSupport.DEFAULT_SCAVENGE_SEC);

        SessionTestSupport server = new SessionTestSupport(0, oldMaxInactive, scavengeSec, cacheFactory, storeFactory);
        ServletContextHandler ctxA = server.addContext("/mod");
        ctxA.addServlet(TestModServlet.class, "/test");

        server.start();
        int port = server.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // Perform a request to create a session
                ContentResponse response = client.GET("http://localhost:" + port + "/mod/test?action=create");

                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                //do another request to reduce the maxinactive interval
                Request request = client.newRequest("http://localhost:" + port + "/mod/test?action=change&val=" + newMaxInactive);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());

                // Wait for the session to expire
                Thread.sleep(TimeUnit.SECONDS.toMillis(newMaxInactive + scavengeSec));

                //do another request using the cookie to ensure the session is NOT there
                request = client.newRequest("http://localhost:" + port + "/mod/test?action=test&val=" + newMaxInactive);
                response = request.send();
                assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
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
    public void testIncreaseMaxInactiveInterval() throws Exception
    {
        int oldMaxInactive = 1;
        int newMaxInactive = 10;
        int scavengeSec = __scavenge;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(SessionTestSupport.DEFAULT_SCAVENGE_SEC);

        SessionTestSupport server = new SessionTestSupport(0, oldMaxInactive, scavengeSec, cacheFactory, storeFactory);
        ServletContextHandler ctxA = server.addContext("/mod");
        ctxA.addServlet(TestModServlet.class, "/test");

        server.start();
        int port = server.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // Perform a request to create a session
                ContentResponse response = client.GET("http://localhost:" + port + "/mod/test?action=create");

                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                //do another request to increase the maxinactive interval
                Request request = client.newRequest("http://localhost:" + port + "/mod/test?action=change&val=" + newMaxInactive);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());

                // wait until the old inactive interval should have expired
                Thread.sleep(TimeUnit.SECONDS.toMillis(scavengeSec + oldMaxInactive));

                //do another request using the cookie to ensure the session is still there
                request = client.newRequest("http://localhost:" + port + "/mod/test?action=test&val=" + newMaxInactive);
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

    @Test
    public void testSetMaxInactiveIntervalWithImmortalSessionAndEviction() throws Exception
    {
        int oldMaxInactive = -1;
        int newMaxInactive = 120; //2min
        int evict = 2;
        int sleep = evict;
        int scavenge = __scavenge;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(evict);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(SessionTestSupport.DEFAULT_SCAVENGE_SEC);

        SessionTestSupport server = new SessionTestSupport(0, oldMaxInactive, scavenge, cacheFactory, storeFactory);
        ServletContextHandler ctxA = server.addContext("/mod");
        ctxA.addServlet(TestModServlet.class, "/test");

        server.start();
        int port = server.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // Perform a request to create a session
                ContentResponse response = client.GET("http://localhost:" + port + "/mod/test?action=create");

                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                //do another request to reduce the maxinactive interval
                Request request = client.newRequest("http://localhost:" + port + "/mod/test?action=change&val=" + newMaxInactive + "&wait=" + sleep);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());

                //do another request using the cookie to ensure the session is still there
                request = client.newRequest("http://localhost:" + port + "/mod/test?action=test&val=" + newMaxInactive);
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

    @Test
    public void testSetMaxInactiveIntervalWithNonImmortalSessionAndEviction() throws Exception
    {
        int oldMaxInactive = 10;
        int newMaxInactive = 2;
        int evict = 4;
        int sleep = evict;
        int scavenge = __scavenge;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(evict);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(SessionTestSupport.DEFAULT_SCAVENGE_SEC);

        SessionTestSupport server = new SessionTestSupport(0, oldMaxInactive, scavenge, cacheFactory, storeFactory);
        ServletContextHandler ctxA = server.addContext("/mod");
        ctxA.addServlet(TestModServlet.class, "/test");

        server.start();
        int port = server.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // Perform a request to create a session
                ContentResponse response = client.GET("http://localhost:" + port + "/mod/test?action=create");

                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                //do another request to reduce the maxinactive interval
                Request request = client.newRequest("http://localhost:" + port + "/mod/test?action=change&val=" + newMaxInactive + "&wait=" + sleep);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());

                //do another request using the cookie to ensure the session is still there
                request = client.newRequest("http://localhost:" + port + "/mod/test?action=test&val=" + newMaxInactive);
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

    @Test
    public void testChangeMaxInactiveIntervalForImmortalSessionNoEviction() throws Exception
    {
        int oldMaxInactive = -1;
        int newMaxInactive = 120;
        int scavenge = __scavenge;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(SessionTestSupport.DEFAULT_SCAVENGE_SEC);

        SessionTestSupport server = new SessionTestSupport(0, oldMaxInactive, scavenge, cacheFactory, storeFactory);
        ServletContextHandler ctxA = server.addContext("/mod");
        ctxA.addServlet(TestModServlet.class, "/test");

        server.start();
        int port = server.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // Perform a request to create a session
                ContentResponse response = client.GET("http://localhost:" + port + "/mod/test?action=create");

                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                //do another request to change the maxinactive interval
                Request request = client.newRequest("http://localhost:" + port + "/mod/test?action=change&val=" + newMaxInactive + "&wait=" + 2);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());

                //do another request using the cookie to ensure the session is still there
                request = client.newRequest("http://localhost:" + port + "/mod/test?action=test&val=" + newMaxInactive);
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

    @Test
    public void testNoExpireSessionInUse() throws Exception
    {
        int maxInactive = 3;
        int scavenge = __scavenge;
        int sleep = maxInactive + scavenge;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(SessionTestSupport.DEFAULT_SCAVENGE_SEC);

        SessionTestSupport server = new SessionTestSupport(0, maxInactive, scavenge, cacheFactory, storeFactory);
        ServletContextHandler ctxA = server.addContext("/mod");
        ctxA.addServlet(TestModServlet.class, "/test");

        server.start();
        int port = server.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // Perform a request to create a session
                ContentResponse response = client.GET("http://localhost:" + port + "/mod/test?action=create");

                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                //do another request that will sleep long enough for the session expiry time to have passed
                //before trying to access the session and ensure it is still there
                Request request = client.newRequest("http://localhost:" + port + "/mod/test?action=sleep&val=" + sleep);
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

    @Test
    public void testSessionExpiryAfterModifiedMaxInactiveInterval() throws Exception
    {
        int oldMaxInactive = 4;
        int newMaxInactive = 20;
        int sleep = oldMaxInactive + __scavenge;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(SessionTestSupport.DEFAULT_SCAVENGE_SEC);

        SessionTestSupport server = new SessionTestSupport(0, oldMaxInactive, __scavenge, cacheFactory, storeFactory);
        ServletContextHandler ctxA = server.addContext("/mod");
        ctxA.addServlet(TestModServlet.class, "/test");

        server.start();
        int port = server.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // Perform a request to create a session                
                ContentResponse response = client.GET("http://localhost:" + port + "/mod/test?action=create");

                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                //do another request to change the maxinactive interval
                Request request = client.newRequest("http://localhost:" + port + "/mod/test?action=change&val=" + newMaxInactive);
                response = request.send();

                assertEquals(HttpServletResponse.SC_OK, response.getStatus());

                //wait for longer than the old inactive interval
                Thread.sleep(TimeUnit.SECONDS.toMillis(sleep));

                //do another request using the cookie to ensure the session is still there
                request = client.newRequest("http://localhost:" + port + "/mod/test?action=test&val=" + newMaxInactive);
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

    @Test
    public void testGetMaxInactiveIntervalWithNegativeMaxInactiveInterval() throws Exception
    {
        int maxInactive = -1;
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(SessionTestSupport.DEFAULT_SCAVENGE_SEC);

        SessionTestSupport server = new SessionTestSupport(0, maxInactive, __scavenge, cacheFactory, storeFactory);
        ServletContextHandler ctxA = server.addContext("/mod");
        ctxA.addServlet(TestModServlet.class, "/test");

        server.start();
        int port = server.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // Perform a request to create a session
                ContentResponse response = client.GET("http://localhost:" + port + "/mod/test?action=create");

                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                //Test that the maxInactiveInterval matches the expected value
                Request request = client.newRequest("http://localhost:" + port + "/mod/test?action=test&val=" + maxInactive);
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

    public static class TestModServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");

            if ("create".equals(action))
            {
                HttpSession session = request.getSession(true);
                assertNotNull(session);
                return;
            }

            if ("change".equals(action))
            {
                //change the expiry time for the session, maybe sleeping before the change
                String tmp = request.getParameter("val");
                int interval = -1;
                interval = (tmp == null ? -1 : Integer.parseInt(tmp));

                tmp = request.getParameter("wait");
                int wait = (tmp == null ? 0 : Integer.parseInt(tmp));
                if (wait > 0)
                {
                    try
                    {
                        Thread.sleep(TimeUnit.SECONDS.toMillis(wait));
                    }
                    catch (Exception e)
                    {
                        throw new ServletException(e);
                    }
                }
                HttpSession session = request.getSession(false);
                if (session == null)
                    throw new ServletException("Session is null for action=change");

                if (interval > 0)
                    session.setMaxInactiveInterval(interval);

                session = request.getSession(false);
                if (session == null)
                    throw new ServletException("Null session after maxInactiveInterval change");
                return;
            }

            if ("sleep".equals(action))
            {
                //sleep before trying to access the session

                HttpSession session = request.getSession(false);
                if (session == null)
                    throw new ServletException("Session is null for action=sleep");

                String tmp = request.getParameter("val");
                int interval = 0;
                interval = (tmp == null ? 0 : Integer.parseInt(tmp));

                if (interval > 0)
                {
                    try
                    {
                        Thread.sleep(TimeUnit.SECONDS.toMillis(interval));
                    }
                    catch (Exception e)
                    {
                        throw new ServletException(e);
                    }
                }

                session = request.getSession(false);
                if (session == null)
                    throw new ServletException("Session null after sleep");

                return;
            }

            if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                if (session == null)
                {
                    response.sendError(500, "Session does not exist");
                    return;
                }
                String tmp = request.getParameter("val");
                int interval = 0;
                interval = (tmp == null ? 0 : Integer.parseInt(tmp));

                assertEquals(interval, session.getMaxInactiveInterval());
                return;
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
