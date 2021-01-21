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
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AsyncTest
 *
 * Tests async handling wrt sessions.
 */
public class AsyncTest
{
    @Test
    public void testSessionWithAsyncDispatch() throws Exception
    {
        // Test async dispatch back to same context, which then creates a session.

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        TestServer server = new TestServer(0, -1, -1, cacheFactory, storeFactory);

        String contextPath = "";
        String mapping = "/server";

        ServletContextHandler contextHandler = server.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server.getServerConnector().addBean(scopeListener);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        contextHandler.addServlet(holder, mapping);

        server.start();
        int port = server.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port + contextPath + mapping + "?action=async";

            //make a request to set up a session on the server
            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
       
            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);
            
            //session should now be evicted from the cache after request exited
            String id = TestServer.extractSessionId(sessionCookie);
            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testSessionWithAsyncComplete() throws Exception
    {
        // Test async write, which creates a session and completes outside of a dispatch

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        TestServer server = new TestServer(0, -1, -1, cacheFactory, storeFactory);

        String contextPath = "";
        String mapping = "/server";

        ServletContextHandler contextHandler = server.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server.getServerConnector().addBean(scopeListener);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        contextHandler.addServlet(holder, mapping);

        server.start();
        int port = server.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port + contextPath + mapping + "?action=asyncComplete";

            //make a request to set up a session on the server
            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            String id = TestServer.extractSessionId(sessionCookie);

            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);

            //session should now be evicted from the cache after request exited
            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testSessionWithCrossContextAsync() throws Exception
    {
        // Test async dispatch from context A to context B then
        // async dispatch back to context B, which then creates a session (in context B).

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        TestServer server = new TestServer(0, -1, -1, cacheFactory, storeFactory);

        ServletContextHandler contextA = server.addContext("/ctxA");
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server.getServerConnector().addBean(scopeListener); //just pick one of the contexts to register the listener
        CrossContextServlet ccServlet = new CrossContextServlet();
        ServletHolder ccHolder = new ServletHolder(ccServlet);
        contextA.addServlet(ccHolder, "/*");

        ServletContextHandler contextB = server.addContext("/ctxB");
        TestServlet testServlet = new TestServlet();
        ServletHolder testHolder = new ServletHolder(testServlet);
        contextB.addServlet(testHolder, "/*");


        server.start();
        int port = server.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port + "/ctxA/test?action=async";

            //make a request to set up a session on the server
            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);

            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);

            //session should now be evicted from the cache after request exited
            String id = TestServer.extractSessionId(sessionCookie);
            assertFalse(contextB.getSessionHandler().getSessionCache().contains(id));
            assertTrue(contextB.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testSessionCreatedBeforeDispatch() throws Exception
    {

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        TestServer server = new TestServer(0, -1, -1, cacheFactory, storeFactory);

        String contextPath = "";
        String mapping = "/server";

        ServletContextHandler contextHandler = server.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server.getServerConnector().addBean(scopeListener);

        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        contextHandler.addServlet(holder, mapping);


        server.start();
        int port = server.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port + contextPath + mapping + "?action=asyncWithSession";

            //make a request to set up a session on the server
            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            String id = TestServer.extractSessionId(sessionCookie);
            
            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);

            //session should now be evicted from the cache after request exited
            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));
        }
        finally
        {
            server.stop();
        }   
    }

    @Test
    public void testSessionWithCrossContextAsyncComplete() throws Exception
    {
        // Test async dispatch from context A to context B, which then does an
        // async write, which creates a session (in context A) and completes outside of a
        // dispatch

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        TestServer server = new TestServer(0, -1, -1, cacheFactory, storeFactory);

        ServletContextHandler contextA = server.addContext("/ctxA");
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server.getServerConnector().addBean(scopeListener);
        CrossContextServlet ccServlet = new CrossContextServlet();
        ServletHolder ccHolder = new ServletHolder(ccServlet);
        contextA.addServlet(ccHolder, "/*");

        ServletContextHandler contextB = server.addContext("/ctxB");
        TestServlet testServlet = new TestServlet();
        ServletHolder testHolder = new ServletHolder(testServlet);
        contextB.addServlet(testHolder, "/*");

        server.start();
        int port = server.getPort();
        HttpClient client = new HttpClient();
        
        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            
            client.start();
            String url = "http://localhost:" + port + "/ctxA/test?action=asyncComplete";

            //make a request to set up a session on the server
            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            
            assertTrue(sessionCookie != null);
            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);

            //session should now be evicted from the cache A after request exited
            String id = TestServer.extractSessionId(sessionCookie);
            assertFalse(contextA.getSessionHandler().getSessionCache().contains(id));
            assertTrue(contextA.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));
        }
        finally
        {
            client.stop();
            server.stop();
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
                TestFoo testFoo = new TestFoo();
                testFoo.setInt(33);
                FooInvocationHandler handler = new FooInvocationHandler(testFoo);
                Foo foo = (Foo)Proxy
                    .newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[]{Foo.class}, handler);
                session.setAttribute("foo", foo);
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                if (session == null)
                    response.sendError(500, "Session not activated");
                Foo foo = (Foo)session.getAttribute("foo");
                if (foo == null || foo.getInt() != 33)
                    response.sendError(500, "Foo not deserialized");
            }
            else if ("async".equals(action))
            {
                if (request.getAttribute("async-test") == null)
                {
                    request.setAttribute("async-test", Boolean.TRUE);
                    AsyncContext acontext = request.startAsync();
                    acontext.dispatch();
                    return;
                }
                else
                {
                    HttpSession session = request.getSession(true);
                    response.getWriter().println("OK");
                }
            }
            else if ("asyncWithSession".equals(action))
            {
                if (request.getAttribute("asyncWithSession") == null)
                {
                    request.setAttribute("asyncWithSession", Boolean.TRUE);
                    AsyncContext acontext = request.startAsync();
                    HttpSession session = request.getSession(true);
                    acontext.dispatch();
                    return;
                }
                else
                {
                    response.getWriter().println("OK");
                }

            }
            else if ("asyncComplete".equals(action))
            {
                AsyncContext acontext = request.startAsync();
                ServletOutputStream out = response.getOutputStream();
                out.setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        if (out.isReady())
                        {
                            HttpSession s = request.getSession(true);
                            out.print("OK\n");
                            acontext.complete();
                        }
                    }

                    @Override
                    public void onError(Throwable t)
                    {

                    }
                });
            }
        }
    }

    public static class CrossContextServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            AsyncContext acontext = request.startAsync();

            acontext.dispatch(request.getServletContext().getContext("/ctxB"), "/test");
        }
    }
}
