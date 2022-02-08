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

package org.eclipse.jetty.server.session;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionEvictionFailureTest
 */
public class SessionEvictionFailureTest
{
    /**
     * MockSessionDataStore
     */
    public static class MockSessionDataStore extends AbstractSessionDataStore
    {
        public boolean[] _nextStoreResult;
        public int i = 0;

        public MockSessionDataStore(boolean[] results)
        {
            _nextStoreResult = results;
        }

        @Override
        public boolean isPassivating()
        {
            return false;
        }

        @Override
        public boolean doExists(String id) throws Exception
        {
            return true;
        }

        @Override
        public SessionData doLoad(String id) throws Exception
        {
            return null;
        }

        @Override
        public boolean delete(String id) throws Exception
        {
            return false;
        }

        @Override
        public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
        {
            if (_nextStoreResult != null && !_nextStoreResult[i++])
            {
                throw new IllegalStateException("Testing store");
            }
        }

        @Override
        public Set<String> doCheckExpired(Set<String> candidates, long timeLimit)
        {
            return candidates;
        }

        @Override
        public Set<String> doGetExpired(long timeLimit)
        {
            return Collections.emptySet();
        }

        @Override
        public void doCleanOrphans(long timeLimit)
        {
            //noop
        }
    }

    /**
     * MockSessionDataStoreFactory
     */
    public static class MockSessionDataStoreFactory extends AbstractSessionDataStoreFactory
    {
        public boolean[] _nextStoreResults;

        public void setNextStoreResults(boolean[] storeResults)
        {
            _nextStoreResults = storeResults;
        }

        @Override
        public SessionDataStore getSessionDataStore(SessionHandler handler) throws Exception
        {
            return new MockSessionDataStore(_nextStoreResults);
        }
    }

    public static class TestServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("aaa", 0);
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                Integer count = (Integer)session.getAttribute("aaa");
                assertNotNull(count);
                session.setAttribute("aaa", (count.intValue() + 1));
            }
        }
    }

    public static void pause(int sec) throws InterruptedException
    {
        Thread.currentThread().sleep(TimeUnit.SECONDS.toMillis(sec));
    }

    @Test
    public void testEvictFailure() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 0;
        int scavengePeriod = 20;
        int evictionPeriod = 5; //evict after  inactivity

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(evictionPeriod);
        SessionDataStoreFactory storeFactory = new MockSessionDataStoreFactory();

        TestServer server = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        ServletContextHandler context = server.addContext(contextPath);
        context.getSessionHandler().getSessionCache().setSaveOnInactiveEviction(true);
        //test values: allow first save, fail evict save, allow save, fail evict save, allow save, allow save on shutdown
        MockSessionDataStore ds = new MockSessionDataStore(new boolean[]{true, false, true, false, true, true});
        context.getSessionHandler().getSessionCache().setSessionDataStore(ds);

        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        context.addServlet(holder, servletMapping);

        try
        {
            server.start();
            int port1 = server.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try (StacklessLogging stackless = new StacklessLogging(SessionEvictionFailureTest.class.getPackage()))
            {
                String url = "http://localhost:" + port1 + contextPath + servletMapping;

                // Create the session
                ContentResponse response = client.GET(url + "?action=init");
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertNotNull(sessionCookie);

                //Wait for the eviction period to expire - save on evict should fail but session
                //should remain in the cache
                pause(evictionPeriod + (int)(evictionPeriod * 0.5));

                // Make another request to see if the session is still in the cache and can be used,
                //allow it to be saved this time
                assertTrue(context.getSessionHandler().getSessionCache().contains(TestServer.extractSessionId(sessionCookie)));
                Request request = client.newRequest(url + "?action=test");
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                assertNull(response.getHeaders().get("Set-Cookie")); //check that the cookie wasn't reset

                //Wait for the eviction period to expire again
                pause(evictionPeriod + (int)(evictionPeriod * 0.5));

                request = client.newRequest(url + "?action=test");
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
}
