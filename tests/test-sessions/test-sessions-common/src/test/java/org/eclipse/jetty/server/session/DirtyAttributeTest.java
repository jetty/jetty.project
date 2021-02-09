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
import java.io.Serializable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionEvent;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DirtyAttributeTest
 *
 * Check that repeated calls to setAttribute when we never evict the
 * session from the cache still result in writes.
 */
public class DirtyAttributeTest
{
    public static TestValue A_VALUE = new TestValue();
    public static TestValue B_VALUE = new TestValue();
    public static String THE_NAME = "__theName";
    public static int INACTIVE = 4;
    public static int SCAVENGE = 1;

    public class TestPassivatingSessionDataStore extends TestSessionDataStore
    {

        /**
         * @see org.eclipse.jetty.server.session.TestSessionDataStore#isPassivating()
         */
        @Override
        public boolean isPassivating()
        {
            return true;
        }
    }

    public class TestPassivatingSessionDataStoreFactory extends AbstractSessionDataStoreFactory
    {

        /**
         * @see org.eclipse.jetty.server.session.SessionDataStoreFactory#getSessionDataStore(org.eclipse.jetty.server.session.SessionHandler)
         */
        @Override
        public SessionDataStore getSessionDataStore(SessionHandler handler) throws Exception
        {
            return new TestPassivatingSessionDataStore();
        }
    }

    @Test
    public void testDirtyWrite() throws Exception
    {
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = new TestPassivatingSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(SCAVENGE);

        TestServer server = new TestServer(0, INACTIVE, SCAVENGE, cacheFactory, storeFactory);

        ServletContextHandler ctxA = server.addContext("/mod");
        ctxA.addServlet(TestDirtyServlet.class, "/test");

        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server.getServerConnector().addBean(scopeListener);

        server.start();
        int port = server.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // Perform a request to create a session
                CountDownLatch latch = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(latch);
                ContentResponse response = client.GET("http://localhost:" + port + "/mod/test?action=create");

                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                
                //ensure request finished
                latch.await(5, TimeUnit.SECONDS);

                //do another request to change the session attribute
                latch = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(latch);
                Request request = client.newRequest("http://localhost:" + port + "/mod/test?action=setA");
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());

                //ensure request fully finished processing
                assertTrue(latch.await(5, TimeUnit.SECONDS));

                A_VALUE.assertPassivatesEquals(1);
                A_VALUE.assertActivatesEquals(1);
                A_VALUE.assertBindsEquals(1);
                A_VALUE.assertUnbindsEquals(0);

                //do another request using the cookie to try changing the session attribute to the same value again  
                latch = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(latch);
                request = client.newRequest("http://localhost:" + port + "/mod/test?action=setA");
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                //ensure request fully finished processing
                latch.await(5, TimeUnit.SECONDS);
                A_VALUE.assertPassivatesEquals(2);
                A_VALUE.assertActivatesEquals(2);
                A_VALUE.assertBindsEquals(1);
                A_VALUE.assertUnbindsEquals(0);

                //do another request using the cookie and change to a different value
                latch = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(latch);
                request = client.newRequest("http://localhost:" + port + "/mod/test?action=setB");
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                latch.await(5, TimeUnit.SECONDS);
                B_VALUE.assertPassivatesEquals(1);
                B_VALUE.assertActivatesEquals(1);
                B_VALUE.assertBindsEquals(1);
                B_VALUE.assertUnbindsEquals(0);
                A_VALUE.assertBindsEquals(1);
                A_VALUE.assertUnbindsEquals(1);
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

    public static class TestValue implements HttpSessionActivationListener, HttpSessionBindingListener, Serializable
    {
        int passivates = 0;
        int activates = 0;
        int binds = 0;
        int unbinds = 0;

        /**
         * @see javax.servlet.http.HttpSessionActivationListener#sessionWillPassivate(javax.servlet.http.HttpSessionEvent)
         */
        @Override
        public void sessionWillPassivate(HttpSessionEvent se)
        {
            ++passivates;
        }

        /**
         * @see javax.servlet.http.HttpSessionActivationListener#sessionDidActivate(javax.servlet.http.HttpSessionEvent)
         */
        @Override
        public void sessionDidActivate(HttpSessionEvent se)
        {
            ++activates;
        }

        public void assertPassivatesEquals(int expected)
        {
            assertEquals(expected, passivates);
        }

        public void assertActivatesEquals(int expected)
        {
            assertEquals(expected, activates);
        }

        public void assertBindsEquals(int expected)
        {
            assertEquals(expected, binds);
        }

        public void assertUnbindsEquals(int expected)
        {
            assertEquals(expected, unbinds);
        }

        /**
         * @see javax.servlet.http.HttpSessionBindingListener#valueBound(javax.servlet.http.HttpSessionBindingEvent)
         */
        @Override
        public void valueBound(HttpSessionBindingEvent event)
        {
            ++binds;
        }

        /**
         * @see javax.servlet.http.HttpSessionBindingListener#valueUnbound(javax.servlet.http.HttpSessionBindingEvent)
         */
        @Override
        public void valueUnbound(HttpSessionBindingEvent event)
        {
            ++unbinds;
        }
    }

    public static class TestDirtyServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");

            if ("create".equals(action))
            {
                HttpSession session = request.getSession(true);
                return;
            }

            if ("setA".equals(action))
            {
                HttpSession session = request.getSession(false);
                if (session == null)
                    throw new ServletException("Session is null for action=change");

                session.setAttribute(THE_NAME, A_VALUE);
                return;
            }

            if ("setB".equals(action))
            {
                HttpSession session = request.getSession(false);
                if (session == null)
                    throw new ServletException("Session does not exist");
                session.setAttribute(THE_NAME, B_VALUE);
                return;
            }
        }
    }
}
