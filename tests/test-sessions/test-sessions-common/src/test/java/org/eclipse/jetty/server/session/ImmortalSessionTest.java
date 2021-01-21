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
import java.io.PrintWriter;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImmortalSessionTest
 */
public class ImmortalSessionTest
{

    @Test
    public void testImmortalSessionNoEviction() throws Exception
    {
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = new NullSessionDataStoreFactory();
        doTest(2, cacheFactory, storeFactory);
    }

    @Test
    public void testImmortalSessionEvictOnExit() throws Exception
    {
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        doTest(2, cacheFactory, storeFactory);
    }

    @Test
    public void testImmortalSessionEvictOnIdle() throws Exception
    {
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.EVICT_ON_INACTIVITY);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        doTest(2, cacheFactory, storeFactory);
    }

    public void doTest(int scavengeInterval, SessionCacheFactory cacheFactory, SessionDataStoreFactory storeFactory)
        throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";

        //turn off session expiry by setting maxInactiveInterval to -1
        TestServer server = new TestServer(0, -1, scavengeInterval, cacheFactory, storeFactory);
        ServletContextHandler context = server.addContext(contextPath);
        context.addServlet(TestServlet.class, servletMapping);

        try
        {
            server.start();
            int port = server.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                int value = 42;
                ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=set&value=" + value);
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                String resp = response.getContentAsString();
                assertEquals(resp.trim(), String.valueOf(value));

                // Let's wait for the scavenger to run, waiting 2.5 times the scavenger period
                Thread.sleep(scavengeInterval * 2500L);

                // Be sure the session is still there
                Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=get");

                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                resp = response.getContentAsString();
                assertEquals(String.valueOf(value), resp.trim());

                assertEquals(1, context.getSessionHandler().getSessionsCreated());
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

    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String result = null;
            String action = request.getParameter("action");
            if ("set".equals(action))
            {
                String value = request.getParameter("value");
                HttpSession session = request.getSession(true);
                session.setAttribute("value", value);
                result = value;
            }
            else if ("get".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                if (session != null)
                    result = (String)session.getAttribute("value");
            }
            PrintWriter writer = response.getWriter();
            writer.println(result);
            writer.flush();
        }
    }
}
