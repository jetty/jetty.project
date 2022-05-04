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
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * RedirectSessionTest
 *
 * Test that creating a session and then doing a redirect preserves the session.
 * 
 * TODO move to ee10
 */
public class RedirectSessionTest
{

    @Test
    public void testSessionRedirect() throws Exception
    {

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        SessionTestSupport testServer = new SessionTestSupport(0, -1, -1, cacheFactory, storeFactory);
        ServletContextHandler testServletContextHandler = testServer.addContext("/context");
        testServletContextHandler.addServlet(Servlet1.class, "/one");
        testServletContextHandler.addServlet(Servlet2.class, "/two");

        try
        {
            testServer.start();
            int serverPort = testServer.getPort();
            HttpClient client = new HttpClient();
            client.setFollowRedirects(true); //ensure client handles redirects
            client.start();
            try
            {
                //make a request to the first servlet, which will redirect
                ContentResponse response = client.GET("http://localhost:" + serverPort + "/context/one");
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
            //create a session
            HttpSession session = request.getSession(true);
            assertNotNull(session);
            session.setAttribute("servlet1", "servlet1");
            response.sendRedirect("/context/two");
        }
    }

    public static class Servlet2 extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            //the session should exist after the redirect
            HttpSession sess = request.getSession(false);
            assertNotNull(sess);
            assertNotNull(sess.getAttribute("servlet1"));
            assertEquals("servlet1", sess.getAttribute("servlet1"));
        }
    }
}
