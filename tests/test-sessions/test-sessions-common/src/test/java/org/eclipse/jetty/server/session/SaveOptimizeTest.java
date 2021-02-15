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
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SaveOptimizeTest
 *
 * Test session save optimization.
 */
public class SaveOptimizeTest
{
    protected TestServlet _servlet;
    protected TestServer _server1 = null;

    /**
     * Create and then invalidate a session in the same request.
     * Use SessionCache.setSaveOnCreate(true) AND save optimization
     * and verify the session is actually saved.
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
        TestSessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        storeFactory.setSavePeriodSec(10);
        _server1 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        _servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = _server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        _servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        _server1.start();
        int port1 = _server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            try
            {
                client.start();
                String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=create&check=true";

                //make a request to set up a session on the server
                ContentResponse response = client.GET(url);
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            _server1.stop();
        }
    }

    /**
     * Test that repeated requests to a session where nothing changes does not do
     * saves.
     */
    @Test
    public void testCleanSessionWithinSavePeriod() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 600;
        int scavengePeriod = 30;
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cacheFactory.setSaveOnCreate(true);
        TestSessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        storeFactory.setSavePeriodSec(300);
        _server1 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        _servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = _server1.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        _server1.getServerConnector().addBean(scopeListener);
        contextHandler.addServlet(holder, servletMapping);
        _servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        _server1.start();
        int port1 = _server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            try
            {
                client.start();
                String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=create&check=true";

                //make a request to set up a session on the server
                CountDownLatch synchronizer = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(synchronizer);
                ContentResponse response = client.GET(url);
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertNotNull(sessionCookie);
                String sessionId = TestServer.extractSessionId(sessionCookie);
                
                //ensure request has finished being handled
                synchronizer.await(5, TimeUnit.SECONDS);

                SessionData data = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(data);

                //make a few requests to access the session but not change it
                int initialNumSaves = getNumSaves();
                for (int i = 0; i < 5; i++)
                {
                    // Perform a request with the same session cookie
                    synchronizer = new CountDownLatch(1);
                    scopeListener.setExitSynchronizer(synchronizer);
                    client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=noop").send();

                    //ensure request has finished being handled
                    synchronizer.await(5, TimeUnit.SECONDS);
                    
                    //check session is unchanged
                    SessionData d = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                    assertNotNull(d);
                    assertThat(getNumSaves(), equalTo(initialNumSaves));

                    //slight pause between requests
                    Thread.sleep(500);
                }
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            _server1.stop();
        }
    }

    /**
     * Test that a dirty session will always be saved regardless of
     * save optimisation.
     */
    @Test
    public void testDirtySession() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 600;
        int scavengePeriod = 30;
        int savePeriod = 5;
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cacheFactory.setSaveOnCreate(true);
        TestSessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        storeFactory.setSavePeriodSec(savePeriod);
        _server1 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        _servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = _server1.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        _server1.getServerConnector().addBean(scopeListener);
        contextHandler.addServlet(holder, servletMapping);
        _servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        _server1.start();
        int port1 = _server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            try
            {
                client.start();
                String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=create&check=true";

                //make a request to set up a session on the server
                CountDownLatch synchronizer = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(synchronizer);
                ContentResponse response = client.GET(url);
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertNotNull(sessionCookie);
                String sessionId = TestServer.extractSessionId(sessionCookie);

                //ensure request has finished being handled
                synchronizer.await(5, TimeUnit.SECONDS);
                
                SessionData data = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(data);

                // Perform a request to do nothing with the same session cookie
                int numSavesBefore = getNumSaves();
                synchronizer = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(synchronizer);
                client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=noop").send();
                
                //ensure request has finished being handled
                synchronizer.await(5, TimeUnit.SECONDS);
                
                //check session not saved
                SessionData d = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(d);
                assertThat(getNumSaves(), equalTo(numSavesBefore));

                // Perform a request to mutate the session
                numSavesBefore = getNumSaves();
                synchronizer = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(synchronizer);
                client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=mutate").send();

                //ensure request has finished being handled
                synchronizer.await(5, TimeUnit.SECONDS);
                
                //check session is saved
                d = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(d);
                assertThat(getNumSaves(), greaterThan(numSavesBefore));
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            _server1.stop();
        }
    }

    /**
     * Test that if the savePeriod is set, the session will only be saved
     * after the savePeriod expires (if not dirty).
     */
    @Test
    public void testCleanSessionAfterSavePeriod() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 600;
        int scavengePeriod = 30;
        int savePeriod = 5;
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cacheFactory.setSaveOnCreate(true);
        TestSessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        storeFactory.setSavePeriodSec(savePeriod);
        _server1 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        _servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = _server1.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        _server1.getServerConnector().addBean(scopeListener);
        contextHandler.addServlet(holder, servletMapping);
        _servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        _server1.start();
        int port1 = _server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            try
            {
                client.start();
                String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=create&check=true";

                //make a request to set up a session on the server
                CountDownLatch synchronizer = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(synchronizer);
                ContentResponse response = client.GET(url);
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertNotNull(sessionCookie);
                String sessionId = TestServer.extractSessionId(sessionCookie);

                //ensure request has finished being handled
                synchronizer.await(5, TimeUnit.SECONDS);
                
                SessionData data = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(data);
                long lastSaved = data.getLastSaved();

                //make another request, session should not change

                // Perform a request to do nothing with the same session cookie
                synchronizer = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(synchronizer);
                client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=noop").send();
               
                //ensure request has finished being handled
                synchronizer.await(5, TimeUnit.SECONDS);
                
                //check session not saved
                SessionData d = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(d);
                assertEquals(lastSaved, d.getLastSaved());

                //wait for the savePeriod to pass and then make another request, this should save the session
                Thread.sleep(1000 * savePeriod);

                // Perform a request to do nothing with the same session cookie
                synchronizer = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(synchronizer);
                client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=noop").send();
                
                //ensure request has finished being handled
                synchronizer.await(5, TimeUnit.SECONDS);
                
                //check session is saved
                d = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(d);
                assertTrue(d.getLastSaved() > lastSaved);
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            _server1.stop();
        }
    }

    /**
     * Test that if we turn off caching of the session, then if a savePeriod
     * is set, the session is still not saved unless the savePeriod expires.
     */
    @Test
    public void testNoCacheWithSaveOptimization() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = -1;
        int scavengePeriod = -1;
        int savePeriod = 10;
        //never cache sessions
        NullSessionCacheFactory cacheFactory = new NullSessionCacheFactory();
        cacheFactory.setSaveOnCreate(true);
        TestSessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        //optimize saves
        storeFactory.setSavePeriodSec(savePeriod);
        _server1 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        _servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = _server1.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        _server1.getServerConnector().addBean(scopeListener);
        contextHandler.addServlet(holder, servletMapping);
        _servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        _server1.start();
        int port1 = _server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            try
            {
                client.start();
                String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=create&check=true";

                //make a request to set up a session on the server
                CountDownLatch synchronizer = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(synchronizer);
                ContentResponse response = client.GET(url);
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertNotNull(sessionCookie);
                String sessionId = TestServer.extractSessionId(sessionCookie);
                
                //ensure request has finished being handled
                synchronizer.await(5, TimeUnit.SECONDS);

                SessionData data = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(data);
                long lastSaved = data.getLastSaved();
                assertTrue(lastSaved > 0); //check session created was saved

                // Perform a request to do nothing with the same session cookie, check the session object is different
                synchronizer = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(synchronizer);
                client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=noop&check=diff").send();

                //ensure request has finished being handled
                synchronizer.await(5, TimeUnit.SECONDS);
                
                //check session not saved
                SessionData d = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(d);
                assertEquals(lastSaved, d.getLastSaved());
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            _server1.stop();
        }
    }

    /**
     * Test changing the maxInactive on a session that is subject to save
     * optimizations, and check that the session is saved, even if it is
     * not otherwise dirty.
     */
    @Test
    public void testChangeMaxInactiveWithSaveOptimisation() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = -1;
        int scavengePeriod = -1;
        int savePeriod = 40;
        //never cache sessions
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setSaveOnCreate(true);
        TestSessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        //optimize saves
        storeFactory.setSavePeriodSec(savePeriod);
        _server1 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        _servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = _server1.addContext(contextPath);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        _server1.getServerConnector().addBean(scopeListener);
        contextHandler.addServlet(holder, servletMapping);
        _servlet.setStore(contextHandler.getSessionHandler().getSessionCache().getSessionDataStore());
        _server1.start();
        int port1 = _server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            HttpClient client = new HttpClient();
            try
            {
                client.start();
                String url = "http://localhost:" + port1 + contextPath + servletMapping + "?action=create&check=true";

                //make a request to set up a session on the server
                int numSavesBefore = getNumSaves();
                CountDownLatch latch = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(latch);
                ContentResponse response = client.GET(url);
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertNotNull(sessionCookie);
                String sessionId = TestServer.extractSessionId(sessionCookie);

                //ensure request fully finished processing
                assertTrue(latch.await(5, TimeUnit.SECONDS));

                //check the SessionData has been saved since before the request
                assertThat(getNumSaves(), greaterThan(numSavesBefore));

                // Perform a request to change maxInactive on session
                numSavesBefore = getNumSaves();
                latch = new CountDownLatch(1);
                scopeListener.setExitSynchronizer(latch);
                client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=max&value=60").send();

                //ensure request fully finished processing
                assertTrue(latch.await(5, TimeUnit.SECONDS));

                //check session is saved, even though the save optimisation interval has not passed
                assertThat(getNumSaves(), greaterThan(numSavesBefore));
                SessionData d = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(d);
                assertEquals(60000, d.getMaxInactiveMs());
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            _server1.stop();
        }
    }

    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;
        public String _id = null;
        public SessionDataStore _store;
        public HttpSession _firstSession = null;

        public void setStore(SessionDataStore store)
        {
            _store = store;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");

            if (action != null && action.startsWith("create"))
            {
                HttpSession session = request.getSession(true);
                _firstSession = session;
                _id = session.getId();
                session.setAttribute("value", 1);

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
            }
            else if ("mutate".equalsIgnoreCase(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                session.setAttribute("ttt", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            }
            else if ("max".equalsIgnoreCase(action))
            {
                int interval = Integer.parseInt(request.getParameter("value"));
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                session.setMaxInactiveInterval(interval);
            }
            else
            {
                //Don't change the session
                HttpSession session = request.getSession(false);
                assertNotNull(session);

                String check = request.getParameter("check");
                if (!StringUtil.isBlank(check) && "same".equalsIgnoreCase(check))
                {
                    assertEquals(_firstSession, session);
                }
                else if (!StringUtil.isBlank(check) && "diff".equalsIgnoreCase(check))
                {
                    assertNotEquals(_firstSession, session);
                }
            }
        }
    }

    private int getNumSaves()
    {
        assertNotNull(_servlet);
        assertNotNull(_servlet._store);
        assertTrue(TestSessionDataStore.class.isInstance(_servlet._store));
        return ((TestSessionDataStore)_servlet._store)._numSaves.get();
    }
}
