//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.Future;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;


public abstract class AbstractSessionRenewTest
{
    public abstract AbstractTestServer createServer(int port, int max, int scavenge);
    
    public void testSessionRenewal() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int scavengePeriod = 3;
        AbstractTestServer server = createServer(0, 1, scavengePeriod);
        ServletContextHandler context = server.addContext(contextPath);
        context.addServlet(TestServlet.class, servletMapping);
        server.start();
        int port=server.getPort();

        HttpClient client = new HttpClient();
        try
        {
            client.start();

            //make a request to create a session
            Future<ContentResponse> future = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=create");
            ContentResponse response = future.get();
            assertEquals(HttpServletResponse.SC_OK,response.getStatus());

            String sessionCookie = response.getHeaders().getStringField("Set-Cookie");
            assertTrue(sessionCookie != null);

            //make a request to change the sessionid
            Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=renew");
            request.header("Cookie", sessionCookie);
            future = request.send();
            ContentResponse renewResponse = future.get();
            assertEquals(HttpServletResponse.SC_OK,renewResponse.getStatus());
            String renewSessionCookie = renewResponse.getHeaders().getStringField("Set-Cookie");
            assertNotNull(renewSessionCookie);
            assertNotSame(sessionCookie, renewSessionCookie);
        }
        finally 
        {
            client.stop();
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
                String beforeSessionId = beforeSession.getId();
                                
                assertTrue(beforeSession != null);
                
                ((AbstractSession)beforeSession).renewId(request);
                
                HttpSession afterSession = request.getSession(false);
                String afterSessionId = afterSession.getId();
                
                assertTrue(afterSession != null);
                assertTrue(beforeSession==afterSession);
                assertTrue(beforeSessionId != afterSessionId);    
                
                AbstractSessionManager sessionManager = (AbstractSessionManager)((AbstractSession)afterSession).getSessionManager();
                AbstractSessionIdManager sessionIdManager = (AbstractSessionIdManager)sessionManager.getSessionIdManager();
                
                assertTrue(sessionIdManager.idInUse(afterSessionId));
                assertFalse(sessionIdManager.idInUse(beforeSessionId));
                
                HttpSession session = sessionManager.getSession(afterSessionId);
                assertNotNull(session);
                session = sessionManager.getSession(beforeSessionId);
                assertNull(session);
             
                if (((AbstractSession)afterSession).isIdChanged())
                {
                    ((org.eclipse.jetty.server.Response)response).addCookie(sessionManager.getSessionCookie(afterSession, request.getContextPath(), request.isSecure()));
                }
            }
        }
    }
    
}
