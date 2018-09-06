//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

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
     * @throws Exception
     */
    @Test
    public void testSessionRenewalNullCache() throws Exception
    {
        SessionCacheFactory cacheFactory = new NullSessionCacheFactory();
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        
        //make the server with a NullSessionCache
        _server = new TestServer(0, -1, -1, cacheFactory, storeFactory);
        doTest(new RenewalVerifier());
    }
    
    
    /**
     * Test renewing session id when sessions are cached
     * @throws Exception
     */
    @Test
    public void testSessionRenewalDefaultCache() throws Exception
    {
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        
        _server = new TestServer(0, -1, -1, cacheFactory, storeFactory);
        
        doTest(new RenewalVerifier() {

            @Override
            public void verify(WebAppContext context, String oldSessionId, String newSessionId)
            throws Exception
            {
                //verify the contents of the cache changed
                assertTrue(context.getSessionHandler().getSessionCache().contains(newSessionId));
                assertFalse(context.getSessionHandler().getSessionCache().contains(oldSessionId));
                super.verify(context, oldSessionId, newSessionId);
            }
            
        });
    }
    

    /**
     *  Perform the test by making a request to create a session
     * then another request that will renew the session id.
     * @param verifier the class that verifies the session id changes in cache/store
     * @throws Exception
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
            int port=_server.getPort();
            
            client.start();

            //make a request to create a session
            ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=create");
            assertEquals(HttpServletResponse.SC_OK,response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            assertFalse(testListener.isCalled());

            //make a request to change the sessionid
            Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=renew");
            ContentResponse renewResponse = request.send();

            assertEquals(HttpServletResponse.SC_OK,renewResponse.getStatus());
            String renewSessionCookie = renewResponse.getHeaders().get("Set-Cookie");
            assertNotNull(renewSessionCookie);
            assertNotSame(sessionCookie, renewSessionCookie);
            assertTrue(testListener.isCalled());

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
     *
     *
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

                ((Session)beforeSession).renewId(request);

                HttpSession afterSession = request.getSession(false);

                assertTrue(afterSession != null);
                String afterSessionId = afterSession.getId();

                assertTrue(beforeSession==afterSession); //same object
                assertFalse(beforeSessionId.equals(afterSessionId)); //different id

                SessionHandler sessionManager = ((Session)afterSession).getSessionHandler();
                DefaultSessionIdManager sessionIdManager = (DefaultSessionIdManager)sessionManager.getSessionIdManager();

                assertTrue(sessionIdManager.isIdInUse(afterSessionId)); //new session id should be in use
                assertFalse(sessionIdManager.isIdInUse(beforeSessionId));

                HttpSession session = sessionManager.getSession(afterSessionId);
                assertNotNull(session);
                session = sessionManager.getSession(beforeSessionId);
                assertNull(session);

                if (((Session)afterSession).isIdChanged())
                {
                    ((org.eclipse.jetty.server.Response)response).addCookie(sessionManager.getSessionCookie(afterSession, request.getContextPath(), request.isSecure()));
                }
            }
        }
    }

}
