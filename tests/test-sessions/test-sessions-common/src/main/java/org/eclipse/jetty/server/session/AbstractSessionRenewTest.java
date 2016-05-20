//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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


/**
 * AbstractSessionRenewTest
 *
 * Test that changes the session id during a request.
 */
public abstract class AbstractSessionRenewTest extends AbstractTestBase
{
    protected AbstractTestServer _server;
    

    public abstract boolean verifyChange (WebAppContext context, String oldSessionId, String newSessionId);
    
    /**
     * @throws Exception
     */
    public void testSessionRenewal() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int maxInactive = 1;
        int scavengePeriod = 3;
        _server = createServer(0, maxInactive, scavengePeriod, SessionCache.NEVER_EVICT);
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
            request.header("Cookie", sessionCookie);
            ContentResponse renewResponse = request.send();

            assertEquals(HttpServletResponse.SC_OK,renewResponse.getStatus());
            String renewSessionCookie = renewResponse.getHeaders().get("Set-Cookie");
            assertNotNull(renewSessionCookie);
            assertNotSame(sessionCookie, renewSessionCookie);
            assertTrue(testListener.isCalled());
            
            assertTrue(verifyChange(context, AbstractTestServer.extractSessionId(sessionCookie), AbstractTestServer.extractSessionId(renewSessionCookie)));
        }
        finally
        {
            client.stop();
            _server.stop();
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
