//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.servlet.ListenerHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.Source;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
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
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        TestServer server1 = new TestServer(0, -1, -1, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
      
        ListenerHolder h = contextHandler.getServletHandler().newListenerHolder(Source.EMBEDDED);
        h.setListener(new MySessionListener());
        contextHandler.getServletHandler().addListener(h);
        
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server1.getServerConnector().addBean(scopeListener);
        contextHandler.addServlet(holder, servletMapping);
        servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        server1.start();
        int port1 = server1.getPort();
        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            
            //make a session
            String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=create&check=false";

            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);

            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);
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
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        TestServer server1 = new TestServer(0, -1, -1, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server1.getServerConnector().addBean(scopeListener);
        contextHandler.addServlet(holder, servletMapping);
        servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=create&check=false";

            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);

            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);

            //session should now be evicted from the cache
            String id = TestServer.extractSessionId(sessionCookie);
            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));

            synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            //make another request for the same session
            Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=test");
            response = request.send();
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
   
            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);
            
            //session should now be evicted from the cache again
            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(TestServer.extractSessionId(sessionCookie)));
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
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        TestServer server1 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server1.getServerConnector().addBean(scopeListener);
        contextHandler.addServlet(holder, servletMapping);
        servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=createinv&check=false";

            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);

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
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        TestServer server1 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server1.getServerConnector().addBean(scopeListener);
        contextHandler.addServlet(holder, servletMapping);
        servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=createinv&check=true";

            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            synchronizer.await(5, TimeUnit.SECONDS);

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
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        TestServer server1 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server1.getServerConnector().addBean(scopeListener);
        contextHandler.addServlet(holder, servletMapping);
        servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=createinvcreate&check=false";

            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);

            //check that the session does not exist
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
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        TestServer server1 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server1.getServerConnector().addBean(scopeListener);
        contextHandler.addServlet(holder, servletMapping);
        ServletContextHandler ctxB = server1.addContext(contextB);
        ctxB.addServlet(TestServletB.class, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            ContentResponse response = client.GET(url + "?action=forward");
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            //wait for request to have exited server completely
            synchronizer.await(5, TimeUnit.SECONDS);

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
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        TestServer server1 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server1.getServerConnector().addBean(scopeListener);
        contextHandler.addServlet(holder, servletMapping);
        ServletContextHandler ctxB = server1.addContext(contextB);
        ctxB.addServlet(TestServletB.class, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            ContentResponse response = client.GET(url + "?action=forwardinv");
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            //wait for request to have exited server completely
            synchronizer.await(5, TimeUnit.SECONDS);

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
                session.setAttribute("value", new Integer(1));

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
                session.setAttribute("value", new Integer(1));

                String check = request.getParameter("check");
                if (!StringUtil.isBlank(check) && _store != null)
                {
                    boolean exists;
                    try
                    {
                        exists = _store.exists(_id);
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
                    assertNull(request.getSession(false));
                    assertNotNull(session);
                    session = request.getSession(true);
                    _id = session.getId();
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
