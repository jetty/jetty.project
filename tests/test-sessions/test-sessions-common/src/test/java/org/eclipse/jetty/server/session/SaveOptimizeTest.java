//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

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
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.Test;



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
     * @throws Exception
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
                String url = "http://localhost:" + port1 + contextPath + servletMapping+"?action=create&check=true";

                //make a request to set up a session on the server
                ContentResponse response = client.GET(url);
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
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
     * @throws Exception
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
                String url = "http://localhost:" + port1 + contextPath + servletMapping+"?action=create&check=true";

                //make a request to set up a session on the server
                ContentResponse response = client.GET(url);
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                String sessionId = TestServer.extractSessionId(sessionCookie);


                SessionData data = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(data);
                long firstSaved = data.getLastSaved();

                //make a few requests to access the session but not change it
                for (int i=0;i<5; i++)
                {
                    // Perform a request to contextB with the same session cookie
                    Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping+"?action=noop");
                    response = request.send();

                    //check session is unchanged
                    SessionData d = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                    assertNotNull(d);
                    assertEquals(firstSaved, d.getLastSaved());

                    //slight pause between requests
                    Thread.currentThread().sleep(500);
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
     * 
     * @throws Exception
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
                String url = "http://localhost:" + port1 + contextPath + servletMapping+"?action=create&check=true";

                //make a request to set up a session on the server
                ContentResponse response = client.GET(url);
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                String sessionId = TestServer.extractSessionId(sessionCookie);


                SessionData data = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(data);           
                long lastSaved = data.getLastSaved();


                // Perform a request to do nothing with the same session cookie
                Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping+"?action=noop");
                response = request.send();

                //check session not saved
                SessionData d = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(d);
                assertEquals(lastSaved, d.getLastSaved());

                // Perform a request to mutate the session
                request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping+"?action=mutate");
                response = request.send();

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
     * Test that if the savePeriod is set, the session will only be saved
     * after the savePeriod expires (if not dirty).
     * @throws Exception
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
                String url = "http://localhost:" + port1 + contextPath + servletMapping+"?action=create&check=true";

                //make a request to set up a session on the server
                ContentResponse response = client.GET(url);
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                String sessionId = TestServer.extractSessionId(sessionCookie);


                SessionData data = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(data);           
                long lastSaved = data.getLastSaved();

                //make another request, session should not change

                // Perform a request to do nothing with the same session cookie
                Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping+"?action=noop");
                response = request.send();

                //check session not saved
                SessionData d = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(d);
                assertEquals(lastSaved, d.getLastSaved());

                //wait for the savePeriod to pass and then make another request, this should save the session
                Thread.currentThread().sleep(1000*savePeriod);

                // Perform a request to do nothing with the same session cookie
                request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping+"?action=noop");
                response = request.send();

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
     * @throws Exception
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
                String url = "http://localhost:" + port1 + contextPath + servletMapping+"?action=create&check=true";

                //make a request to set up a session on the server
                ContentResponse response = client.GET(url);
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                String sessionId = TestServer.extractSessionId(sessionCookie);

                SessionData data = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(data);           
                long lastSaved = data.getLastSaved();
                assertTrue(lastSaved > 0); //check session created was saved

                // Perform a request to do nothing with the same session cookie, check the session object is different
                Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping+"?action=noop&check=diff");
                response = request.send();

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
     * 
     * @throws Exception
     */
    @Test
    public void testChangeMaxInactiveWithSaveOptimisation () throws Exception
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
                String url = "http://localhost:" + port1 + contextPath + servletMapping+"?action=create&check=true";

                //make a request to set up a session on the server
                ContentResponse response = client.GET(url);
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                String sessionId = TestServer.extractSessionId(sessionCookie);

                SessionData data = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(data);           
                long lastSaved = data.getLastSaved();
                assertTrue(lastSaved > 0); //check session created was saved

                // Perform a request to change maxInactive on session
                Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping+"?action=max&value=60");
                response = request.send();

                //check session is saved, even though the save optimisation interval has not passed
                SessionData d = contextHandler.getSessionHandler().getSessionCache().getSessionDataStore().load(sessionId);
                assertNotNull(d);
                assertTrue(d.getLastSaved() > lastSaved);
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
        public String _id = null;
        public SessionDataStore _store;
        public HttpSession _firstSession = null;


        public void setStore (SessionDataStore store)
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
                        throw new ServletException (e);
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
                session.setAttribute("ttt", new Long(System.currentTimeMillis()));
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
    
}
