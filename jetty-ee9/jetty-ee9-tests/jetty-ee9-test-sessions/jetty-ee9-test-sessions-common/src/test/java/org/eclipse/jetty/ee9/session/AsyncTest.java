//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.awaitility.Awaitility;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.eclipse.jetty.session.test.Foo;
import org.eclipse.jetty.session.test.FooInvocationHandler;
import org.eclipse.jetty.session.test.TestFoo;
import org.eclipse.jetty.session.test.TestSessionDataStoreFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        cacheFactory.setFlushOnResponseCommit(true);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        SessionTestSupport server = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);

        String contextPath = "";
        String mapping = "/server";

        ServletContextHandler contextHandler = server.addContext(contextPath);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        contextHandler.addServlet(holder, mapping);

        server.start();
        int port = server.getPort();

        try (StacklessLogging stackless = new StacklessLogging(AsyncTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port + contextPath + mapping + "?action=async";

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertNotNull(sessionCookie);
            
            //session should now be evicted from the cache after request exited
            String id = SessionTestSupport.extractSessionId(sessionCookie);
            assertTrue(contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore().exists(id));
            Awaitility.waitAtMost(5, TimeUnit.SECONDS).until(() -> !contextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(id));
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
        cacheFactory.setFlushOnResponseCommit(true);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        SessionTestSupport server = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);

        String contextPath = "";
        String mapping = "/server";

        ServletContextHandler contextHandler = server.addContext(contextPath);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        contextHandler.addServlet(holder, mapping);

        server.start();
        int port = server.getPort();

        try (StacklessLogging stackless = new StacklessLogging(AsyncTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port + contextPath + mapping + "?action=asyncComplete";

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertNotNull(sessionCookie);
            String id = SessionTestSupport.extractSessionId(sessionCookie);

            //session should now be evicted from the cache after request exited
            assertTrue(contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore().exists(id));
            Awaitility.waitAtMost(5, TimeUnit.SECONDS).until(() -> !contextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertFalse(contextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(id));
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testSimpleCrossContextAsync() throws Exception
    {
        //Test async cross context dispatch from context A to context B
        Server server = new Server();
        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();

        final List<String> events = new ArrayList<>();

        ServletContextHandler contextA = new ServletContextHandler();
        contextA.addEventListener(new ServletRequestListener()
        {
            @Override
            public void requestDestroyed(ServletRequestEvent sre)
            {
                events.add("Request Destroyed: " + sre.getServletRequest().getServletContext().getContextPath());
                ServletRequestListener.super.requestDestroyed(sre);
            }

            @Override
            public void requestInitialized(ServletRequestEvent sre)
            {
                events.add("Request Initialized: " + sre.getServletRequest().getServletContext().getContextPath());
                ServletRequestListener.super.requestInitialized(sre);
            }
        });
        contextA.setContextPath("/ctxA");
        contextA.setCrossContextDispatchSupported(true);
        final String ASYNC_FLAG_NAME = "async.flag";

        ServletHolder serviceHolder = new ServletHolder("service-servlet", new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getAttribute(ASYNC_FLAG_NAME) == null)
                {
                    AsyncContext asyncContext = req.startAsync(req, resp);
                    req.setAttribute(ASYNC_FLAG_NAME, new Object());
                    asyncContext.setTimeout(100);
                    asyncContext.addListener(new AsyncListener()
                    {
                        @Override
                        public void onComplete(AsyncEvent event)
                        {
                            events.add("ON complete");
                        }

                        @Override
                        public void onTimeout(AsyncEvent event)
                        {
                            events.add("ON timeout");
                        }

                        @Override
                        public void onError(AsyncEvent event)
                        {
                            events.add("ON error");
                        }

                        @Override
                        public void onStartAsync(AsyncEvent event)
                        {
                            events.add("ON startasync");
                        }
                    });
                    //perform cross context dispatch
                    ServletContext destination = req.getServletContext().getContext("/ctxB");
                    asyncContext.dispatch(destination, "/dispatched/z");
                }
            }
        });

        serviceHolder.setAsyncSupported(true);
        contextA.addServlet(serviceHolder, "/dispatcher/*");
        contextHandlerCollection.addHandler(contextA);

        ServletContextHandler contextB = new ServletContextHandler();
        contextB.setContextPath("/ctxB");
        contextB.setCrossContextDispatchSupported(true);

        ServletHolder testHolder = new ServletHolder("test-servlet", new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.setCharacterEncoding("utf-8");
                resp.setContentType("text/plain");
                resp.getWriter().println("Dispatched to ctxB in test-servlet");
                resp.getWriter().println(req.getQueryString());
            }
        });

        contextB.addServlet(testHolder, "/dispatched/*");
        contextHandlerCollection.addHandler(contextB);
        server.setHandler(contextHandlerCollection);
        LocalConnector connector = new LocalConnector(server);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);
        server.addConnector(connector);

        server.start();

        try
        {

            String rawRequest = """
                GET /ctxA/dispatcher/x?foo=bar HTTP/1.1
                Host: local
                Connection: close
                            
                """;

            HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
            assertThat(response.getStatus(), is(200));
            assertThat(events, Matchers.containsInRelativeOrder("Request Initialized: /ctxA", "Request Destroyed: /ctxA"));
            assertThat(response.getContent(), containsString("Dispatched to ctxB in test-servlet"));
            assertThat(response.getContent(), containsString("foo=bar"));
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
        cacheFactory.setFlushOnResponseCommit(true);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        SessionTestSupport server = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);

        ServletContextHandler contextA = server.addContext("/ctxA");
        CrossContextServlet ccServlet = new CrossContextServlet();
        contextA.setCrossContextDispatchSupported(true);
        ServletHolder ccHolder = new ServletHolder(ccServlet);
        contextA.addServlet(ccHolder, "/*");

        ServletContextHandler contextB = server.addContext("/ctxB");
        contextB.setCrossContextDispatchSupported(true);
        TestServlet testServlet = new TestServlet();
        ServletHolder testHolder = new ServletHolder(testServlet);
        contextB.addServlet(testHolder, "/*");


        server.start();
        int port = server.getPort();

        try (StacklessLogging stackless = new StacklessLogging(AsyncTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port + "/ctxA/test?action=async";

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertNotNull(sessionCookie);

            //session should now be evicted from the cache after request exited
            String id = SessionTestSupport.extractSessionId(sessionCookie);
            Awaitility.waitAtMost(5, TimeUnit.SECONDS).until(() -> !contextB.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertFalse(contextB.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertTrue(contextB.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore().exists(id));
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
        cacheFactory.setFlushOnResponseCommit(true);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        SessionTestSupport server = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);

        String contextPath = "";
        String mapping = "/server";

        ServletContextHandler contextHandler = server.addContext(contextPath);

        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        contextHandler.addServlet(holder, mapping);


        server.start();
        int port = server.getPort();

        try (StacklessLogging stackless = new StacklessLogging(AsyncTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port + contextPath + mapping + "?action=asyncWithSession";

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertNotNull(sessionCookie);
            String id = SessionTestSupport.extractSessionId(sessionCookie);

            //session should now be evicted from the cache after request exited
            Awaitility.waitAtMost(5, TimeUnit.SECONDS).until(() -> !contextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertFalse(contextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore().exists(id));
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
        cacheFactory.setFlushOnResponseCommit(true);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        SessionTestSupport server = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);

        ServletContextHandler contextA = server.addContext("/ctxA");
        contextA.setCrossContextDispatchSupported(true);
        CrossContextServlet ccServlet = new CrossContextServlet();
        ServletHolder ccHolder = new ServletHolder(ccServlet);
        contextA.addServlet(ccHolder, "/*");

        ServletContextHandler contextB = server.addContext("/ctxB");
        contextB.setCrossContextDispatchSupported(true);
        TestServlet testServlet = new TestServlet();
        ServletHolder testHolder = new ServletHolder(testServlet);
        contextB.addServlet(testHolder, "/*");

        server.start();
        int port = server.getPort();
        HttpClient client = new HttpClient();

        try (StacklessLogging stackless = new StacklessLogging(AsyncTest.class.getPackage()))
        {
            client.start();
            String url = "http://localhost:" + port + "/ctxA/test?action=asyncComplete";

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            
            assertTrue(sessionCookie != null);

            //session should now be evicted from the cache A after request exited
            String id = SessionTestSupport.extractSessionId(sessionCookie);
            Awaitility.waitAtMost(5, TimeUnit.SECONDS).until(() -> !contextA.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertFalse(contextA.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertTrue(contextA.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore().exists(id));
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
                    .newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[]{
                        Foo.class
                    }, handler);
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
                            //This test is a really BAD idea - you should not be
                            //creating a HttpSession in a thread that should merely
                            //be performing writes on the response.
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
