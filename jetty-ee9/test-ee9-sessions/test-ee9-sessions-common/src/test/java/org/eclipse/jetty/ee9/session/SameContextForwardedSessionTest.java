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

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SameContextForwardedSessionTest
 *
 * Test that creating a session inside a forward on the same context works, and that
 * attributes set after the forward returns are preserved.
 */
public class SameContextForwardedSessionTest
{

    @Test
    public void testSessionCreateInForward() throws Exception
    {
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        SessionTestSupport testServer = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);

        ServletContextHandler testServletContextHandler = testServer.addContext("/context");
        ServletHolder holder = new ServletHolder(new Servlet1());
        testServletContextHandler.addServlet(holder, "/one");
        testServletContextHandler.addServlet(Servlet2.class, "/two");
        testServletContextHandler.addServlet(Servlet3.class, "/three");
        testServletContextHandler.addServlet(Servlet4.class, "/four");

        try
        {
            testServer.start();
            int serverPort = testServer.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                //make a request to the first servlet, which will forward it to other servlets
                ContentResponse response = client.GET("http://localhost:" + serverPort + "/context/one");
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                //test that the session was created, and that it contains the attributes from servlet3 and servlet1
                testServletContextHandler.getSessionHandler().getSessionManager().getSessionCache().contains(SessionTestSupport.extractSessionId(sessionCookie));
                testServletContextHandler.getSessionHandler().getSessionManager().getSessionCache().getSessionDataStore().exists(SessionTestSupport.extractSessionId(sessionCookie));

                //Make a fresh request
                Request request = client.newRequest("http://localhost:" + serverPort + "/context/four");
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            testServer.stop();
        }
    }

    public static class Servlet1 extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            //Don't create a session, just forward to another session in the same context
            assertNull(request.getSession(false));

            //The session will be created by the other servlet, so will exist as this dispatch returns
            RequestDispatcher dispatcher = request.getServletContext().getRequestDispatcher("/two");
            dispatcher.forward(request, response);

            HttpSession sess = request.getSession(false);
            assertNotNull(sess);
            assertNotNull(sess.getAttribute("servlet3"));
            sess.setAttribute("servlet1", "servlet1");
        }
    }

    public static class Servlet2 extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            //forward to yet another servlet to do the creation
            assertNull(request.getSession(false));

            RequestDispatcher dispatcher = request.getServletContext().getRequestDispatcher("/three");
            dispatcher.forward(request, response);

            //the session should exist after the forward
            HttpSession sess = request.getSession(false);
            assertNotNull(sess);
            assertNotNull(sess.getAttribute("servlet3"));
        }
    }

    public static class Servlet3 extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            //No session yet
            assertNull(request.getSession(false));

            //Create it
            HttpSession session = request.getSession();
            assertNotNull(session);

            //Set an attribute on it
            session.setAttribute("servlet3", "servlet3");
        }
    }

    public static class Servlet4 extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            //Check that the session contains attributes set during and after the session forward
            HttpSession session = request.getSession();
            assertNotNull(session);
            assertNotNull(session.getAttribute("servlet1"));
            assertNotNull(session.getAttribute("servlet3"));
        }
    }
}
