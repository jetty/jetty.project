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

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.ee10.servlet.ListenerHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlet.Source;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionDataStore;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CreationTest
 *
 * Test combinations of creating, forwarding and invalidating
 * a session.
 */
public class CreationTest
{
    static ThreadLocal<HttpServletRequest> currentRequest = new ThreadLocal<>();

    @Test
    public void testRequestGetSessionInsideListener() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
        cacheFactory.setFlushOnResponseCommit(true); //ensure session is saved before response comes back
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        SessionTestSupport server1 = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
      
        ListenerHolder h = contextHandler.getServletHandler().newListenerHolder(Source.EMBEDDED);
        h.setListener(new MySessionListener());
        contextHandler.getServletHandler().addListener(h);
        contextHandler.addServlet(holder, servletMapping);
        servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        server1.start();
        int port1 = server1.getPort();
        try (StacklessLogging stackless = new StacklessLogging(CreationTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            
            //make a session
            String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=create&check=false";

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
        }
        finally
        {
            server1.stop();
        }

    }
    
    
    /**
     * Test creating a session when the cache is set to
     * evict after the request exits.
     */
    @Test
    public void testSessionCreateWithEviction() throws Exception
    {

        String contextPath = "";
        String servletMapping = "/server";

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
        cacheFactory.setFlushOnResponseCommit(true); //ensure session is saved before response comes back
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        SessionTestSupport server1 = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(CreationTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=create&check=false";

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);

            //session should now be evicted from the cache
            String id = SessionTestSupport.extractSessionId(sessionCookie);
            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));

            //make another request for the same session
            Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=test");
            response = request.send();
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            
            //session should now be evicted from the cache again
            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(SessionTestSupport.extractSessionId(sessionCookie)));
        }
        finally
        {
            server1.stop();
        }
    }

    /**
     * Create and then invalidate a session in the same request.
     * Set SessionCache.setSaveOnCreate(false), so that the creation
     * and immediate invalidation of the session means it is never stored.
     */
    @Test
    public void testSessionCreateAndInvalidateNoSave() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 20;
        int scavengePeriod = 3;
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cacheFactory.setFlushOnResponseCommit(true); //ensure session is saved before response comes back
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        SessionTestSupport server1 = new SessionTestSupport(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(CreationTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=createinv&check=false";

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            //check that the session does not exist
            assertFalse(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(servlet._id));
        }
        finally
        {
            server1.stop();
        }
    }

    /**
     * Create and then invalidate a session in the same request.
     * Use SessionCache.setSaveOnCreate(true) and verify the session
     * exists before it is invalidated.
     */
    @Test
    public void testSessionCreateAndInvalidateWithSave() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 20;
        int scavengePeriod = 3;
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cacheFactory.setSaveOnCreate(true);
        cacheFactory.setFlushOnResponseCommit(true); //ensure session is saved before response comes back
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        SessionTestSupport server1 = new SessionTestSupport(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(CreationTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=createinv&check=true";

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            //check that the session does not exist
            assertFalse(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(servlet._id));
        }
        finally
        {
            server1.stop();
        }
    }

    /**
     * Create and then invalidate and then create a session in the same request
     */
    @Test
    public void testSessionCreateInvalidateCreate() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 20;
        int scavengePeriod = 3;
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cacheFactory.setSaveOnCreate(false); //don't immediately save a new session
        cacheFactory.setFlushOnResponseCommit(true); //ensure session is saved before response comes back
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        SessionTestSupport server1 = new SessionTestSupport(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(CreationTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=createinvcreate&check=false";

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            //check that the session exists
            System.err.println("servlet._id " + servlet._id);
            assertTrue(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(servlet._id));
            assertThat(response.getHeaders().getValuesList(HttpHeader.SET_COOKIE).size(), Matchers.is(1));
        }
        finally
        {
            server1.stop();
        }
    }

    /**
     * Create a session in a context, forward to another context and create a
     * session in it too. Check that both sessions exist after the response
     * completes.
     */
    //TODO - no cross context support in jetty-12
    @Disabled
    @Test
    public void testSessionCreateForward() throws Exception
    {
        String contextPath = "";
        String contextB = "/contextB";
        String servletMapping = "/server";
        int inactivePeriod = 20;
        int scavengePeriod = 3;
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cacheFactory.setFlushOnResponseCommit(true); //ensure session is saved before response comes back
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        SessionTestSupport server1 = new SessionTestSupport(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        ServletContextHandler ctxB = server1.addContext(contextB);
        ctxB.addServlet(TestServletB.class, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(CreationTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url + "?action=forward");
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            //check that the sessions exist persisted
            assertTrue(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(servlet._id));
            assertTrue(ctxB.getSessionHandler().getSessionCache().getSessionDataStore().exists(servlet._id));
        }
        finally
        {
            server1.stop();
        }
    }

    /**
     * Create a session in one context, forward to another context and create another session
     * in it, then invalidate the session in the original context: that should invalidate the
     * session in both contexts and no session should exist after the response completes.
     */
    //TODO no cross context dispatch in jetty-12
    @Disabled
    @Test
    public void testSessionCreateForwardAndInvalidate() throws Exception
    {
        String contextPath = "";
        String contextB = "/contextB";
        String servletMapping = "/server";
        int inactivePeriod = 20;
        int scavengePeriod = 3;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cacheFactory.setFlushOnResponseCommit(true); //ensure session is saved before response comes back
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        SessionTestSupport server1 = new SessionTestSupport(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        ServletContextHandler ctxB = server1.addContext(contextB);
        ctxB.addServlet(TestServletB.class, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(CreationTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            ContentResponse response = client.GET(url + "?action=forwardinv");
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            //check that the session does not exist 
            assertFalse(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(servlet._id));
            assertFalse(ctxB.getSessionHandler().getSessionCache().getSessionDataStore().exists(servlet._id));
        }
        finally
        {
            server1.stop();
        }
    }

    public static class MySessionListener implements HttpSessionListener
    {
        @Override
        public void sessionCreated(HttpSessionEvent se)
        {
            currentRequest.get().getSession(true);
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent se)
        {
        }
    }
    
    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;
        public String _id = null;
        public SessionDataStore _store;

        public void setStore(SessionDataStore store)
        {
            _store = store;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");

            if (action != null && action.startsWith("forward"))
            {
                HttpSession session = request.getSession(true);
                
                _id = session.getId();
                session.setAttribute("value", 1);

                ServletContext contextB = getServletContext().getContext("/contextB");
                RequestDispatcher dispatcherB = contextB.getRequestDispatcher(request.getServletPath());
                dispatcherB.forward(request, httpServletResponse);

                if (action.endsWith("inv"))
                {
                    session.invalidate();
                }
                else
                {
                    session = request.getSession(false);
                    assertNotNull(session);
                    assertEquals(_id, session.getId());
                    assertNotNull(session.getAttribute("value"));
                    assertNull(session.getAttribute("B")); //check we don't see stuff from other context
                }
                return;
            }
            else if (action != null && "test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                return;
            }
            else if (action != null && action.startsWith("create"))
            {
                currentRequest.set(request);
                HttpSession session = request.getSession(true);
                _id = session.getId();
                session.setAttribute("value", 1);

                System.err.println("Created session " + _id);
                String check = request.getParameter("check");
                if (!StringUtil.isBlank(check) && _store != null)
                {
                    boolean exists;
                    try
                    {
                        exists = _store.exists(_id);
                        System.err.println("Does session exist in store: " + exists);
                    }
                    catch (Exception e)
                    {
                        throw new ServletException(e);
                    }

                    if ("false".equalsIgnoreCase(check))
                        assertFalse(exists);
                    else
                        assertTrue(exists);
                }

                if ("createinv".equals(action))
                {
                    session.invalidate();
                    assertNull(request.getSession(false));
                    assertNotNull(session);
                }
                else if ("createinvcreate".equals(action))
                {
                    session.invalidate();
                    System.err.println("Session invalidated " + _id);
                    assertNull(request.getSession(false));
                    assertNotNull(session);
                    session = request.getSession(true);
                    _id = session.getId();
                    System.err.println("Created another session " + _id);
                }
            }
        }
    }

    public static class TestServletB extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            HttpSession session = request.getSession(false);
            assertNull(session);
            if (session == null)
                session = request.getSession(true);

            // Be sure nothing from contextA is present
            Object objectA = session.getAttribute("value");
            assertTrue(objectA == null);

            // Add something, so in contextA we can check if it is visible (it must not).
            session.setAttribute("B", "B");
        }
    }
}
