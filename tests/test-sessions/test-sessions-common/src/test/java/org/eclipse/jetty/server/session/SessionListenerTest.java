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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpCookie;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionListenerTest
 *
 * Test that session listeners are called.
 */
@ExtendWith(WorkDirExtension.class)
public class SessionListenerTest
{
    public WorkDir workDir;

    /**
     * Test that listeners are called when a session is deliberately invalidated.
     */
    @Test
    public void testListenerWithInvalidation() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 6;
        int scavengePeriod = -1;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        TestSessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        storeFactory.setGracePeriodSec(scavengePeriod);

        TestServer server = new TestServer(0, inactivePeriod, scavengePeriod,
            cacheFactory, storeFactory);
        ServletContextHandler context = server.addContext(contextPath);
        TestHttpSessionListener listener = new TestHttpSessionListener(true, true);
        context.getSessionHandler().addEventListener(listener);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        context.addServlet(holder, servletMapping);

        try
        {
            server.start();
            int port1 = server.getPort();

            HttpClient client = new HttpClient();
            client.start();
            try
            {
                String url = "http://localhost:" + port1 + contextPath + servletMapping;
                // Create the session
                ContentResponse response1 = client.GET(url + "?action=init");
                assertEquals(HttpServletResponse.SC_OK, response1.getStatus());
                String sessionCookie = response1.getHeaders().get("Set-Cookie");
                assertNotNull(sessionCookie);
                assertTrue(TestServlet.bindingListener.bound);

                String sessionId = TestServer.extractSessionId(sessionCookie);
                assertThat(sessionId, is(in(listener.createdSessions)));

                // Make a request which will invalidate the existing session
                Request request2 = client.newRequest(url + "?action=test");
                ContentResponse response2 = request2.send();
                assertEquals(HttpServletResponse.SC_OK, response2.getStatus());

                assertTrue(TestServlet.bindingListener.unbound);
                assertTrue(listener.destroyedSessions.contains(sessionId));
            }
            finally
            {
                LifeCycle.stop(client);
            }
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }
    
    /**
     * Test that if a session listener throws an exception during sessionDestroyed the session is still invalidated
     */
    @Test
    public void testListenerWithInvalidationException() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 6;
        int scavengePeriod = -1;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        TestSessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        storeFactory.setGracePeriodSec(scavengePeriod);

        TestServer server = new TestServer(0, inactivePeriod, scavengePeriod,
            cacheFactory, storeFactory);
        ServletContextHandler context = server.addContext(contextPath);
        ThrowingSessionListener listener = new ThrowingSessionListener();
        context.getSessionHandler().addEventListener(listener);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        context.addServlet(holder, servletMapping);

        try
        {
            server.start();
            int port1 = server.getPort();

            HttpClient client = new HttpClient();
            client.start();
            try
            {
                String url = "http://localhost:" + port1 + contextPath + servletMapping;
                // Create the session
                ContentResponse response1 = client.GET(url + "?action=init");
                assertEquals(HttpServletResponse.SC_OK, response1.getStatus());
                String sessionCookie = response1.getHeaders().get("Set-Cookie");
                assertNotNull(sessionCookie);
                assertTrue(TestServlet.bindingListener.bound);

                String sessionId = TestServer.extractSessionId(sessionCookie);

                // Make a request which will invalidate the existing session
                Request request2 = client.newRequest(url + "?action=test");
                ContentResponse response2 = request2.send();
                assertEquals(HttpServletResponse.SC_OK, response2.getStatus());

                assertTrue(TestServlet.bindingListener.unbound);
                
                //check session no longer exists
                assertFalse(context.getSessionHandler().getSessionCache().contains(sessionId));
                assertFalse(context.getSessionHandler().getSessionCache().getSessionDataStore().exists(sessionId));
            }
            finally
            {
                LifeCycle.stop(client);
            }
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    /**
     * Test that listeners are called when a session expires
     * and that the listener is able to access webapp classes.
     */
    @Test
    public void testSessionExpiresWithListener() throws Exception
    {
        Path foodir = workDir.getEmptyPathDir();
        Path fooClass = foodir.resolve("Foo.class");

        //Use a class that would only be known to the webapp classloader
        try (InputStream foostream = Thread.currentThread().getContextClassLoader().getResourceAsStream("Foo.clazz");
             OutputStream out = Files.newOutputStream(fooClass))
        {
            IO.copy(foostream, out);
        }

        assertTrue(Files.exists(fooClass));
        assertThat(Files.size(fooClass), greaterThan(0L));

        URL[] foodirUrls = new URL[]{foodir.toUri().toURL()};
        URLClassLoader contextClassLoader = new URLClassLoader(foodirUrls, Thread.currentThread().getContextClassLoader());

        String contextPath = "/";
        String servletMapping = "/server";
        int inactivePeriod = 3;
        int scavengePeriod = 1;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        TestSessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        storeFactory.setGracePeriodSec(scavengePeriod);

        TestServer server1 = new TestServer(0, inactivePeriod, scavengePeriod,
            cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler context = server1.addContext(contextPath);
        context.setClassLoader(contextClassLoader);
        context.addServlet(holder, servletMapping);
        TestHttpSessionListener listener = new TestHttpSessionListenerWithWebappClasses(true, true);
        context.getSessionHandler().addEventListener(listener);

        try
        {
            server1.start();
            int port1 = server1.getPort();

            HttpClient client = new HttpClient();
            try
            {
                client.start();
                String url = "http://localhost:" + port1 + contextPath + servletMapping.substring(1);

                //make a request to set up a session on the server
                ContentResponse response1 = client.GET(url + "?action=init");
                assertEquals(HttpServletResponse.SC_OK, response1.getStatus());
                String sessionCookie = response1.getHeaders().get("Set-Cookie");
                assertNotNull(sessionCookie);

                String sessionId = TestServer.extractSessionId(sessionCookie);

                assertThat(sessionId, is(in(listener.createdSessions)));

                //and wait until the session should have expired
                Thread.sleep(TimeUnit.SECONDS.toMillis(inactivePeriod + (2 * scavengePeriod)));

                assertThat(sessionId, is(in(listener.destroyedSessions)));

                assertNull(listener.attributeException);
                assertNull(listener.accessTimeException);
            }
            finally
            {
                LifeCycle.stop(client);
            }
        }
        finally
        {
            server1.stop();
        }
    }

    /**
     * Check that a session that is expired cannot be reused, and expiry listeners are called for it
     */
    @Test
    public void testExpiredSession() throws Exception
    {
        String contextPath = "/";
        String servletMapping = "/server";
        int inactivePeriod = 4;
        int scavengePeriod = 1;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        TestSessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        storeFactory.setGracePeriodSec(scavengePeriod);

        TestServer server1 = new TestServer(0, inactivePeriod, scavengePeriod,
            cacheFactory, storeFactory);
        SimpleTestServlet servlet = new SimpleTestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler context = server1.addContext(contextPath);
        context.addServlet(holder, servletMapping);
        TestHttpSessionListener listener = new TestHttpSessionListener(true, true);

        context.getSessionHandler().addEventListener(listener);

        try
        {
            server1.start();
            int port1 = server1.getPort();

            //save a session that has already expired
            long now = System.currentTimeMillis();
            SessionData data = context.getSessionHandler().getSessionCache().getSessionDataStore().newSessionData("1234", now - 10, now - 5, now - 10, 30000);
            data.setExpiry(100); //make it expired a long time ago
            context.getSessionHandler().getSessionCache().getSessionDataStore().store("1234", data);

            HttpClient client = new HttpClient();
            try
            {
                client.start();

                port1 = server1.getPort();
                String url = "http://localhost:" + port1 + contextPath + servletMapping.substring(1);

                //make another request using the id of the expired session
                Request request = client.newRequest(url + "?action=test");
                request.cookie(new HttpCookie("JSESSIONID", "1234"));
                ContentResponse response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());

                //should be a new session id
                String cookie2 = response.getHeaders().get("Set-Cookie");
                assertNotEquals("1234", TestServer.extractSessionId(cookie2));

                assertTrue(listener.destroyedSessions.contains("1234"));

                assertNull(listener.attributeException);
                assertNull(listener.accessTimeException);
            }
            finally
            {
                LifeCycle.stop(client);
            }
        }
        finally
        {
            server1.stop();
        }
    }

    public static class MyHttpSessionListener implements HttpSessionListener
    {
        @Override
        public void sessionCreated(HttpSessionEvent se)
        {
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent se)
        {
        }
    }
    
    public static class ThrowingSessionListener implements HttpSessionListener
    {

        @Override
        public void sessionCreated(HttpSessionEvent se)
        {
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent se)
        {
            throw new IllegalStateException("Exception during sessionDestroyed");
        }
        
    }

    @Test
    public void testSessionListeners()
    {
        Server server = new Server();
        try
        {
            WebAppContext wac = new WebAppContext();

            wac.setServer(server);
            server.setHandler(wac);
            wac.addEventListener(new MyHttpSessionListener());

            Collection<MyHttpSessionListener> listeners = wac.getSessionHandler().getBeans(MyHttpSessionListener.class);
            assertNotNull(listeners);

            assertEquals(1, listeners.size());
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    public static class MySessionBindingListener implements HttpSessionBindingListener, Serializable
    {
        private static final long serialVersionUID = 1L;
        boolean unbound = false;
        boolean bound = false;

        public void valueUnbound(HttpSessionBindingEvent event)
        {
            unbound = true;
        }

        public void valueBound(HttpSessionBindingEvent event)
        {
            bound = true;
        }
    }

    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;
        public static final MySessionBindingListener bindingListener = new MySessionBindingListener();

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");

            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("foo", bindingListener);
                assertNotNull(session);
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);

                //invalidate existing session
                session.invalidate();
            }
        }
    }

    public static class SimpleTestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("test".equals(action))
            {
                HttpSession session = request.getSession(true);
                assertNotNull(session);
            }
        }
    }
}
