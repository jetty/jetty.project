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
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionDataStore;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.eclipse.jetty.session.SessionManager;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test having multiple session cookies in a request.
 */
public class DuplicateCookieTest
{
    public class TestSession extends Session
    {

        public TestSession(SessionManager manager, SessionData data)
        {
            super(manager, data);
        }
        
        public void makeInvalid()
        {
            _state = Session.State.INVALID;
        }
    }
     
    public class TestSessionCache extends DefaultSessionCache
    {

        public TestSessionCache(SessionManager manager)
        {
            super(manager);
        }

        @Override
        public Session newSession(SessionData data)
        {
            return new TestSession(getSessionManager(), data);
        }
    }
    
    public class TestSessionCacheFactory extends DefaultSessionCacheFactory
    {

        @Override
        public SessionCache newSessionCache(SessionManager manager)
        {
            return new TestSessionCache(manager);
        }
        
    }
    
    @Test
    public void testMultipleSessionCookiesOnlyOneExists() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        HttpClient client = null;

        TestSessionCacheFactory cacheFactory = new TestSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        SessionTestSupport server1 = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging ignored = new StacklessLogging(DuplicateCookieTest.class.getPackage()))
        {
            //create a valid session
            Session s4422 = createUnExpiredSession(contextHandler.getSessionHandler().getSessionManager().getSessionCache(),
                contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore(),
                "4422");

            client = new HttpClient();
            client.start();

            assertEquals(0, s4422.getRequests());

            //make a request with another session cookie in there that does not exist
            Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=check");
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=123")); //doesn't exist
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=4422")); //does exist
            ContentResponse response = request.send();
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            assertEquals("4422", response.getContentAsString());

            //check session is drained of requests
            assertEquals(0, s4422.getRequests());
        }
        finally
        {
            LifeCycle.stop(server1);
            LifeCycle.stop(client);
        }
    }

    @Test
    public void testMultipleSessionCookiesValidFirst() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        HttpClient client = null;

        TestSessionCacheFactory cacheFactory = new TestSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        SessionTestSupport server1 = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging ignored = new StacklessLogging(DuplicateCookieTest.class.getPackage()))
        {
            //create a valid session
            Session s1122 = createUnExpiredSession(contextHandler.getSessionHandler().getSessionManager().getSessionCache(),
                contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore(),
                "1122");
            //create an invalid session
            Session s2233 = createInvalidSession(contextHandler.getSessionHandler().getSessionManager().getSessionCache(),
                contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore(),
                "2233");
            //create another invalid session
            Session s2255 =  createInvalidSession(contextHandler.getSessionHandler().getSessionManager().getSessionCache(),
                contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore(),
                "2255");

            client = new HttpClient();
            client.start();

            assertEquals(0, s1122.getRequests());
            assertEquals(0, s2233.getRequests());
            assertEquals(0, s2255.getRequests());

            //make a request where the valid session cookie is first
            Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=check");
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=1122")); //is valid
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=2233")); //is invalid
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=2255")); //is invalid
            ContentResponse response = request.send();
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            assertEquals("1122", response.getContentAsString());

            //check valid session is drained of requests
            assertEquals(0, s1122.getRequests());
        }
        finally
        {
            LifeCycle.stop(server1);
            LifeCycle.stop(client);
        }
    }

    @Test
    public void testMultipleSessionCookiesInvalidFirst() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        HttpClient client = null;

        TestSessionCacheFactory cacheFactory = new TestSessionCacheFactory();
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        SessionTestSupport server1 = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging ignored = new StacklessLogging(DuplicateCookieTest.class.getPackage()))
        {
            //create a valid session
            Session s1122 = createUnExpiredSession(contextHandler.getSessionHandler().getSessionManager().getSessionCache(),
                contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore(),
                "1122");
            //create an invalid session
            Session s2233 = createInvalidSession(contextHandler.getSessionHandler().getSessionManager().getSessionCache(),
                contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore(),
                "2233");
            //create another invalid session
            Session s2255 =  createInvalidSession(contextHandler.getSessionHandler().getSessionManager().getSessionCache(),
                contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore(),
                "2255");

            client = new HttpClient();
            client.start();

            assertEquals(0, s1122.getRequests());
            assertEquals(0, s2233.getRequests());
            assertEquals(0, s2255.getRequests());

            //make a request with the valid session cookie last
            // Create the session
            Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=check");
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=2233")); //is invalid
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=2255")); //is invalid
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=1122")); //is valid
            ContentResponse response = request.send();
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            assertEquals("1122", response.getContentAsString());

            //check valid session drained of requests
            assertEquals(0, s1122.getRequests());
        }
        finally
        {
            LifeCycle.stop(server1);
            LifeCycle.stop(client);
        }
    }

    @Test
    public void testMultipleSessionCookiesInvalidValidInvalid() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        HttpClient client = null;

        TestSessionCacheFactory cacheFactory = new TestSessionCacheFactory();
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        SessionTestSupport server1 = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging ignored = new StacklessLogging(DuplicateCookieTest.class.getPackage()))
        {
            //create a valid session
            Session s1122 = createUnExpiredSession(contextHandler.getSessionHandler().getSessionManager().getSessionCache(),
                contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore(),
                "1122");
            //create an invalid session
            Session s2233 = createInvalidSession(contextHandler.getSessionHandler().getSessionManager().getSessionCache(),
                contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore(),
                "2233");
            //create another invalid session
            Session s2255 =  createInvalidSession(contextHandler.getSessionHandler().getSessionManager().getSessionCache(),
                contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore(),
                "2255");

            client = new HttpClient();
            client.start();

            assertEquals(0, s1122.getRequests());
            assertEquals(0, s2233.getRequests());
            assertEquals(0, s2255.getRequests());

            //make a request with another session cookie with the valid session surrounded by invalids
            Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=check");
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=2233")); //is invalid
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=1122")); //is valid
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=2255")); //is invalid
            ContentResponse response = request.send();
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            assertEquals("1122", response.getContentAsString());

            //check valid session drained of requests
            assertEquals(0, s1122.getRequests());
        }
        finally
        {
            LifeCycle.stop(server1);
            LifeCycle.stop(client);
        }
    }

    @Test
    public void testMultipleSessionCookiesMultipleExists() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        HttpClient client = null;

        TestSessionCacheFactory cacheFactory = new TestSessionCacheFactory();
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        SessionTestSupport server1 = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging ignored = new StacklessLogging(DuplicateCookieTest.class.getPackage()))
        {
            //create some unexpired sessions
            Session s1234 = createUnExpiredSession(contextHandler.getSessionHandler().getSessionManager().getSessionCache(),
                contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore(),
                "1234");
            Session s5678 = createUnExpiredSession(contextHandler.getSessionHandler().getSessionManager().getSessionCache(),
                contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore(),
                "5678");
            Session s9111 = createUnExpiredSession(contextHandler.getSessionHandler().getSessionManager().getSessionCache(),
                contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore(),
                "9111");

            client = new HttpClient();
            client.start();

            //check that the request count is 0
            assertEquals(0, s1234.getRequests());
            assertEquals(0, s5678.getRequests());
            assertEquals(0, s9111.getRequests());

            //make a request with multiple valid session ids
            Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=check");
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=1234"));
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=5678"));
            ContentResponse response = request.send();
            assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getStatus());

            //check that all sessions have their request counts decremented correctly after the request, back to 0
            assertEquals(0, s1234.getRequests());
            assertEquals(0, s5678.getRequests());
            assertEquals(0, s9111.getRequests());
        }
        finally
        {
            LifeCycle.stop(server1);
            LifeCycle.stop(client);
        }
    }

    @Test
    public void testMultipleIdenticalSessionCookies() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        HttpClient client = null;

        TestSessionCacheFactory cacheFactory = new TestSessionCacheFactory();
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        SessionTestSupport server1 = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging ignored = new StacklessLogging(DuplicateCookieTest.class.getPackage()))
        {
            //create a valid  unexpired session
            Session s1234 = createUnExpiredSession(contextHandler.getSessionHandler().getSessionManager().getSessionCache(),
                contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore(),
                "1234");

            client = new HttpClient();
            client.start();

            //check that the request count is 0
            assertEquals(0, s1234.getRequests());

            //make a request with multiple valid session ids
            Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=check");
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=1234"));
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=1234"));
            ContentResponse response = request.send();
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            //check that all valid sessions have their request counts decremented correctly after the request, back to 0
            assertEquals(0, s1234.getRequests());
        }
        finally
        {
            LifeCycle.stop(server1);
            LifeCycle.stop(client);
        }
    }

    public Session createUnExpiredSession(SessionCache cache, SessionDataStore store, String id) throws Exception
    {
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData(id, now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        Session s = cache.newSession(data);
        cache.add(id, s);
        cache.release(s); //pretend a request that created the session is finished
        return s;
    }

    public Session createInvalidSession(SessionCache cache, SessionDataStore store, String id) throws Exception
    {
        Session session = createUnExpiredSession(cache, store, id);
        ((TestSession)session).makeInvalid();

        return session;
    }

    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if (StringUtil.isBlank(action))
                return;

            if (action.equalsIgnoreCase("check"))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                httpServletResponse.getWriter().print(session.getId());
            }
            else if (action.equalsIgnoreCase("create"))
            {
                request.getSession(true);
            }
        }
    }
}
