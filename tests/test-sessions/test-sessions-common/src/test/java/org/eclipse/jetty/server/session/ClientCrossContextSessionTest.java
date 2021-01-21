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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClientCrossContextSessionTest
 *
 * Test that a client can create a session on one context and
 * then re-use that session id on a request to another context,
 * but the session contents are separate on each.
 */
public class ClientCrossContextSessionTest
{

    @Test
    public void testCrossContextDispatch() throws Exception
    {
        String contextA = "/contextA";
        String contextB = "/contextB";
        String servletMapping = "/server";

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = new NullSessionDataStoreFactory();

        TestServer server = new TestServer(0, TestServer.DEFAULT_MAX_INACTIVE, TestServer.DEFAULT_SCAVENGE_SEC,
            cacheFactory, storeFactory);
        TestServletA servletA = new TestServletA();
        ServletHolder holderA = new ServletHolder(servletA);
        ServletContextHandler ctxA = server.addContext(contextA);
        ctxA.addServlet(holderA, servletMapping);
        ServletContextHandler ctxB = server.addContext(contextB);
        TestServletB servletB = new TestServletB();
        ServletHolder holderB = new ServletHolder(servletB);
        ctxB.addServlet(holderB, servletMapping);

        try
        {
            server.start();
            int port = server.getPort();

            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // Perform a request to contextA
                ContentResponse response = client.GET("http://localhost:" + port + contextA + servletMapping);

                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                String sessionId = TestServer.extractSessionId(sessionCookie);

                // Perform a request to contextB with the same session cookie
                Request request = client.newRequest("http://localhost:" + port + contextB + servletMapping);
                request.header("Cookie", "JSESSIONID=" + sessionId);
                ContentResponse responseB = request.send();
                assertEquals(HttpServletResponse.SC_OK, responseB.getStatus());
                assertEquals(servletA.sessionId, servletB.sessionId);
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

    public static class TestServletA extends HttpServlet
    {
        private static final long serialVersionUID = 1L;
        public String sessionId;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            HttpSession session = request.getSession(false);
            if (session == null)
            {
                session = request.getSession(true);
                sessionId = session.getId();
            }

            // Add something to the session
            session.setAttribute("A", "A");

            // Check that we don't see things put in session by contextB
            Object objectB = session.getAttribute("B");
            assertTrue(objectB == null);
        }
    }

    public static class TestServletB extends HttpServlet
    {
        private static final long serialVersionUID = 1L;
        public String sessionId;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            HttpSession session = request.getSession(false);
            if (session == null)
                session = request.getSession(true);

            sessionId = session.getId();

            // Add something to the session
            session.setAttribute("B", "B");

            // Check that we don't see things put in session by contextA
            Object objectA = session.getAttribute("A");
            assertTrue(objectA == null);
        }
    }
}
