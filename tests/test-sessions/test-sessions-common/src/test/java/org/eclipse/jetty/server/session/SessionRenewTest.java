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
import java.net.HttpCookie;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionIdListener;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionRenewTest
 *
 * Test that changes the session id during a request.
 */
public class SessionRenewTest
{
    protected TestServer _server;

    /**
     * Tests renewing a session id when sessions are not being cached.
     */
    @Test
    public void testSessionRenewalNullCache() throws Exception
    {
        SessionCacheFactory cacheFactory = new NullSessionCacheFactory();
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        //make the server with a NullSessionCache
        _server = new TestServer(0, -1, -1, cacheFactory, storeFactory);
        doTest(new RenewalVerifier()
            {

                @Override
                public void verify(WebAppContext context, String oldSessionId, String newSessionId) throws Exception
                {
                    //null cache means it should contain neither session
                    assertFalse(context.getSessionHandler().getSessionCache().contains(newSessionId));
                    assertFalse(context.getSessionHandler().getSessionCache().contains(oldSessionId));
                    super.verify(context, oldSessionId, newSessionId);
                }
            
            });
    }

    /**
     * Test renewing session id when sessions are cached
     */
    @Test
    public void testSessionRenewalDefaultCache() throws Exception
    {
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        _server = new TestServer(0, -1, -1, cacheFactory, storeFactory);

        doTest(new RenewalVerifier()
        {

            @Override
            public void verify(WebAppContext context, String oldSessionId, String newSessionId)
                throws Exception
            {
                //verify the contents of the cache changed
                assertTrue(context.getSessionHandler().getSessionCache().contains(newSessionId));
                assertFalse(context.getSessionHandler().getSessionCache().contains(oldSessionId));
                assertFalse(((AbstractSessionCache)context.getSessionHandler().getSessionCache()).doGet(newSessionId).isIdChanged());
                super.verify(context, oldSessionId, newSessionId);
            }
        });
    }

    @Test
    public void testSessionRenewalMultiContext() throws Exception
    {
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        _server = new TestServer(0, -1, -1, cacheFactory, storeFactory);
        
        String contextPathA = "";
        String servletMapping = "/server";
        WebAppContext contextA = _server.addWebAppContext(".", contextPathA);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        _server.getServerConnector().addBean(scopeListener);
        contextA.setParentLoaderPriority(true);
        contextA.addServlet(TestServlet.class, servletMapping);
        
        WebAppContext contextB = _server.addWebAppContext(".", "/B");
        contextB.setParentLoaderPriority(true);

        HttpClient client = new HttpClient();
        try
        {
            _server.start();
            int port = _server.getPort();

            client.start();

            //pre-create session data for both contextA and contextB
            long now = System.currentTimeMillis();
            SessionData dataA = contextA.getSessionHandler().getSessionCache().getSessionDataStore().newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
            contextA.getSessionHandler().getSessionCache().getSessionDataStore().store("1234", dataA);
            SessionData dataB = contextB.getSessionHandler().getSessionCache().getSessionDataStore().newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
            contextB.getSessionHandler().getSessionCache().getSessionDataStore().store("1234", dataB);

            //make a request to change the sessionid
            CountDownLatch synchronizer = new CountDownLatch(1);
            scopeListener.setExitSynchronizer(synchronizer);
            Request request = client.newRequest("http://localhost:" + port + contextPathA + servletMapping + "?action=renew");
            request.cookie(new HttpCookie(SessionHandler.__DefaultSessionCookie, "1234"));
            ContentResponse renewResponse = request.send();
            assertEquals(HttpServletResponse.SC_OK, renewResponse.getStatus());
            String newSessionCookie = renewResponse.getHeaders().get("Set-Cookie");
            assertTrue(newSessionCookie != null);
            String updatedId = TestServer.extractSessionId(newSessionCookie);

            //ensure request has finished being handled
            synchronizer.await(5, TimeUnit.SECONDS);
            
            //session ids should be updated on all contexts
            contextA.getSessionHandler().getSessionCache().contains(updatedId);
            contextB.getSessionHandler().getSessionCache().contains(updatedId);

            Session sessiona = ((AbstractSessionCache)contextA.getSessionHandler().getSessionCache()).getAndEnter(updatedId, false);
            Session sessionb = ((AbstractSessionCache)contextB.getSessionHandler().getSessionCache()).getAndEnter(updatedId, false);

            //sessions should nor have any usecounts
            assertEquals(0, sessiona.getRequests());
            assertEquals(0, sessionb.getRequests());

        }
        finally
        {
            client.stop();
            _server.stop();
        }
    }

    /**
     * Perform the test by making a request to create a session
     * then another request that will renew the session id.
     *
     * @param verifier the class that verifies the session id changes in cache/store
     */
    public void doTest(RenewalVerifier verifier) throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        WebAppContext context = _server.addWebAppContext(".", contextPath);
        context.setParentLoaderPriority(true);
        context.addServlet(TestServlet.class, servletMapping);
        TestHttpSessionIdListener testListener = new TestHttpSessionIdListener();
        context.addEventListener(testListener);

        HttpClient client = new HttpClient();
        try
        {
            _server.start();
            int port = _server.getPort();

            client.start();

            //make a request to create a session
            ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=create");
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            
            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            assertFalse(testListener.isCalled());

            //make a request to change the sessionid
            Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=renew");
            ContentResponse renewResponse = request.send();
            assertEquals(HttpServletResponse.SC_OK, renewResponse.getStatus());
            
            String renewSessionCookie = renewResponse.getHeaders().get("Set-Cookie");
            assertNotNull(renewSessionCookie);
            assertNotSame(sessionCookie, renewSessionCookie);
            assertTrue(testListener.isCalled());
            
            request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=check");
            ContentResponse checkResponse = request.send();
            assertEquals(HttpServletResponse.SC_OK, checkResponse.getStatus());
            assertNull(checkResponse.getHeaders().get("Set-Cookie"));

            if (verifier != null)
                verifier.verify(context, TestServer.extractSessionId(sessionCookie), TestServer.extractSessionId(renewSessionCookie));
        }
        finally
        {
            client.stop();
            _server.stop();
        }
    }

    /**
     * RenewalVerifier
     */
    public class RenewalVerifier
    {
        public void verify(WebAppContext context, String oldSessionId, String newSessionId)
            throws Exception
        {
            //verify that the session id changed in the session store
            TestSessionDataStore store = (TestSessionDataStore)context.getSessionHandler().getSessionCache().getSessionDataStore();
            assertTrue(store.exists(newSessionId));
            assertFalse(store.exists(oldSessionId));
        }
    }

    public static class TestHttpSessionIdListener implements HttpSessionIdListener
    {
        boolean called = false;

        @Override
        public void sessionIdChanged(HttpSessionEvent event, String oldSessionId)
        {
            assertNotNull(event.getSession());
            assertNotSame(oldSessionId, event.getSession().getId());
            called = true;
        }

        public boolean isCalled()
        {
            return called;
        }
    }

    public static class TestServletB extends HttpServlet
    {

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
           //Ensure a session exists
        }
        
    }

    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("create".equals(action))
            {
                HttpSession session = request.getSession(true);
                assertTrue(session.isNew());
            }
            else if ("renew".equals(action))
            {
                HttpSession beforeSession = request.getSession(false);
                assertTrue(beforeSession != null);
                String beforeSessionId = beforeSession.getId();

                //((Session)beforeSession).renewId(request);
                request.changeSessionId();

                HttpSession afterSession = request.getSession(false);

                assertTrue(afterSession != null);
                String afterSessionId = afterSession.getId();

                assertTrue(beforeSession == afterSession); //same object
                assertFalse(beforeSessionId.equals(afterSessionId)); //different id

                SessionHandler sessionManager = ((Session)afterSession).getSessionHandler();
                DefaultSessionIdManager sessionIdManager = (DefaultSessionIdManager)sessionManager.getSessionIdManager();

                assertTrue(sessionIdManager.isIdInUse(afterSessionId)); //new session id should be in use
                assertFalse(sessionIdManager.isIdInUse(beforeSessionId));
            }
            else
            {
                request.getSession(false);
            }
        }
    }
}
