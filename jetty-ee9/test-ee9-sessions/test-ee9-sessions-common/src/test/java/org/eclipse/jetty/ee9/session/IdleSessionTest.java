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
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.NullSessionCacheFactory;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.eclipse.jetty.util.thread.AutoLock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IdleSessionTest
 *
 * Checks that a session can be passivated and re-activated on the next request if it hasn't expired.
 */
public class IdleSessionTest
{

    protected TestServlet _servlet = new TestServlet();
    protected SessionTestSupport _server1 = null;

    public void pause(int sec) throws InterruptedException
    {
        Thread.sleep(TimeUnit.SECONDS.toMillis(sec));
    }

    /**
     *
     */
    @Test
    public void testSessionIdle() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 10;
        int scavengePeriod = 1;
        int evictionSec = 2; //evict from cache if idle for 2 sec

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(evictionSec);
        cacheFactory.setFlushOnResponseCommit(true);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        _server1 = new SessionTestSupport(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = _server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        _server1.start();
        int port1 = _server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(IdleSessionTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server

            ContentResponse response = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertNotNull(sessionCookie);

            //and wait until the session should be passivated out
            pause(evictionSec * 2);
            
            //check that the session has been idled
            String id = SessionTestSupport.extractSessionId(sessionCookie);
            assertFalse(contextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore().exists(id));

            //make another request to reactivate the session
            Request request = client.newRequest(url + "?action=test");
            ContentResponse response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
            
            //check session reactivated
            assertTrue(contextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(id));

            //wait again for the session to be passivated
            pause(evictionSec * 2);

            //check that it is
            assertFalse(contextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore().exists(id));

            //While passivated, take some action to ensure that a reactivate won't work, like
            //deleting the sessions in the store
            ((TestSessionDataStore)contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore())._map.clear();

            //make a request
            request = client.newRequest(url + "?action=testfail");
            response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
            
            //Test trying to reactivate an expired session (ie before the scavenger can get to it)
            //make a request to set up a session on the server
            response = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            sessionCookie = response.getHeaders().get("Set-Cookie");
            assertNotNull(sessionCookie);
            id = SessionTestSupport.extractSessionId(sessionCookie);

            //and wait until the session should be idled out
            pause(evictionSec * 2);

            //stop the scavenger
            if (_server1.getHouseKeeper() != null)
                _server1.getHouseKeeper().stop();

            //check that the session is passivated
            assertFalse(contextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore().exists(id));

            //wait until the session should be expired
            pause(inactivePeriod + (3 * scavengePeriod));

            //make another request to reactivate the session
            request = client.newRequest(url + "?action=testfail");
            response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
            assertFalse(contextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertFalse(contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore().exists(id));
        }
        finally
        {
            _server1.stop();
        }
    }

    @Test
    public void testNullSessionCache() throws Exception
    {
        //test the NullSessionCache which does not support idle timeout
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 20;
        int scavengePeriod = 3;

        NullSessionCacheFactory cacheFactory = new NullSessionCacheFactory();
        cacheFactory.setFlushOnResponseCommit(true);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        _server1 = new SessionTestSupport(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = _server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        _server1.start();
        int port1 = _server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(IdleSessionTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertNotNull(sessionCookie);

            //the session should never be cached
            String id = SessionTestSupport.extractSessionId(sessionCookie);
            assertFalse(contextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore().exists(id));

            //make another request to reactivate the session
            Request request = client.newRequest(url + "?action=test");
            ContentResponse response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
            
            //check session still not in the cache
            assertFalse(contextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore().exists(id));

            //While passivated, take some action to ensure that a reactivate won't work, like
            //deleting the sessions in the store
            ((TestSessionDataStore)contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore())._map.clear();

            //make a request
            request = client.newRequest(url + "?action=testfail");
            response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
            
            //Test trying to reactivate an expired session (ie before the scavenger can get to it)
            //make a request to set up a session on the server
            response = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            sessionCookie = response.getHeaders().get("Set-Cookie");
            assertNotNull(sessionCookie);
            id = SessionTestSupport.extractSessionId(sessionCookie);
            
            //stop the scavenger
            if (_server1.getHouseKeeper() != null)
                _server1.getHouseKeeper().stop();

            //check that the session is passivated
            assertFalse(contextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore().exists(id));

            //wait until the session should be expired
            pause(inactivePeriod + (3 * scavengePeriod));

            //make another request to reactivate the session
            request = client.newRequest(url + "?action=testfail");
            response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
            
            assertFalse(contextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(id));
            assertFalse(contextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore().exists(id));
        }
        finally
        {
            _server1.stop();
        }
    }

    public static class TestServlet extends HttpServlet
    {
        public String originalId = null;
        public HttpSession _session = null;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("value", 1);
                originalId = session.getId();
                
                Session s = ((org.eclipse.jetty.ee9.nested.Request)request).getCoreSession();
                try (AutoLock lock = s.lock())
                {
                    assertTrue(s.isResident());
                }
                _session = session;
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                assertEquals(originalId, session.getId());
                Session s = ((org.eclipse.jetty.ee9.nested.Request)request).getCoreSession();
                try (AutoLock lock = s.lock())
                {
                    assertTrue(s.isResident());
                }
                Integer v = (Integer)session.getAttribute("value");
                session.setAttribute("value", v + 1);
                _session = session;
            }
            else if ("testfail".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNull(session);
                _session = session;
            }
        }
    }
}
