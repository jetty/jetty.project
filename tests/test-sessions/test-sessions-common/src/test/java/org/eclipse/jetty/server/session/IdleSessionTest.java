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
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.Locker.Lock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IdleSessionTest
 *
 * Checks that a session can be passivated and re-activated on the next request if it hasn't expired.
 */
public class IdleSessionTest
{

    protected TestServlet _servlet = new TestServlet();
    protected TestServer _server1 = null;

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
        int inactivePeriod = 20;
        int scavengePeriod = 3;
        int evictionSec = 5; //evict from cache if idle for 5 sec

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(evictionSec);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        _server1 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = _server1.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        _server1.getServerConnector().addBean(scopeListener);
        contextHandler.addServlet(holder, servletMapping);
        _server1.start();
        int port1 = _server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            ContentResponse response = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            
            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);

            //and wait until the session should be passivated out
            pause(evictionSec * 2);
            
            //check that the session has been idled
            String id = TestServer.extractSessionId(sessionCookie);
            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));

            //make another request to reactivate the session
            synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            Request request = client.newRequest(url + "?action=test");
            ContentResponse response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
            
            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);
            
            //check session reactivated
            assertTrue(contextHandler.getSessionHandler().getSessionCache().contains(id));

            //wait again for the session to be passivated
            pause(evictionSec * 2);

            //check that it is
            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));

            //While passivated, take some action to ensure that a reactivate won't work, like
            //deleting the sessions in the store
            ((TestSessionDataStore)contextHandler.getSessionHandler().getSessionCache().getSessionDataStore())._map.clear();

            //make a request
            synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            request = client.newRequest(url + "?action=testfail");
            response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK, response2.getStatus());

            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);
            
            //Test trying to reactivate an expired session (ie before the scavenger can get to it)
            //make a request to set up a session on the server
            synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            response = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            id = TestServer.extractSessionId(sessionCookie);
            
            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);

            //and wait until the session should be idled out
            pause(evictionSec * 2);

            //stop the scavenger
            if (_server1.getHouseKeeper() != null)
                _server1.getHouseKeeper().stop();

            //check that the session is passivated
            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));

            //wait until the session should be expired
            pause(inactivePeriod + (3 * scavengePeriod));

            //make another request to reactivate the session
            synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            request = client.newRequest(url + "?action=testfail");
            response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
            
            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);

            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(id));
            assertFalse(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));
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
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        _server1 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = _server1.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        _server1.getServerConnector().addBean(scopeListener);
        contextHandler.addServlet(holder, servletMapping);
        _server1.start();
        int port1 = _server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            ContentResponse response = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            
            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);

            //the session should never be cached
            String id = TestServer.extractSessionId(sessionCookie);
            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));

            //make another request to reactivate the session
            synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            Request request = client.newRequest(url + "?action=test");
            ContentResponse response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
            
            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);
            
            //check session still not in the cache
            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));

            //While passivated, take some action to ensure that a reactivate won't work, like
            //deleting the sessions in the store
            ((TestSessionDataStore)contextHandler.getSessionHandler().getSessionCache().getSessionDataStore())._map.clear();

            //make a request
            synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            request = client.newRequest(url + "?action=testfail");
            response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK, response2.getStatus());

            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);
            
            //Test trying to reactivate an expired session (ie before the scavenger can get to it)
            //make a request to set up a session on the server
            synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            response = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            id = TestServer.extractSessionId(sessionCookie);
            
            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);
            

            //stop the scavenger
            if (_server1.getHouseKeeper() != null)
                _server1.getHouseKeeper().stop();

            //check that the session is passivated
            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(id));
            assertTrue(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));

            //wait until the session should be expired
            pause(inactivePeriod + (3 * scavengePeriod));

            //make another request to reactivate the session
            synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            request = client.newRequest(url + "?action=testfail");
            response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
            
            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);
            
            assertFalse(contextHandler.getSessionHandler().getSessionCache().contains(id));
            assertFalse(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));
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
                session.setAttribute("value", new Integer(1));
                originalId = session.getId();
                Session s = (Session)session;
                try (Lock lock = s.lock())
                {
                    assertTrue(s.isResident());
                }
                _session = s;
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session != null);
                assertTrue(originalId.equals(session.getId()));
                Session s = (Session)session;
                try (Lock lock = s.lock();)
                {
                    assertTrue(s.isResident());
                }
                Integer v = (Integer)session.getAttribute("value");
                session.setAttribute("value", new Integer(v.intValue() + 1));
                _session = session;
            }
            else if ("testfail".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session == null);
                _session = session;
            }
        }
    }
}
