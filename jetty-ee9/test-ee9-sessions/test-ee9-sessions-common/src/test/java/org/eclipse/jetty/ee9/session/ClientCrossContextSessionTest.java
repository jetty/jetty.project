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
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.NullSessionDataStoreFactory;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

        SessionTestSupport server = new SessionTestSupport(0, SessionTestSupport.DEFAULT_MAX_INACTIVE, SessionTestSupport.DEFAULT_SCAVENGE_SEC,
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
                assertNotNull(sessionCookie);
                String sessionId = SessionTestSupport.extractSessionId(sessionCookie);

                // Perform a request to contextB with the same session cookie
                Request request = client.newRequest("http://localhost:" + port + contextB + servletMapping);
                HttpField cookie = new HttpField("Cookie", "JSESSIONID=" + sessionId);
                request.headers(headers -> headers.put(cookie));
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
            assertNull(objectB);
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
            assertNull(objectA);
        }
    }
}
