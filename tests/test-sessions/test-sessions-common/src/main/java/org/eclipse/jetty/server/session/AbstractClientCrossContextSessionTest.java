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
import org.junit.Test;


/**
 * AbstractClientCrossContextSessionTest
 */
public abstract class AbstractClientCrossContextSessionTest extends AbstractTestBase
{



    @Test
    public void testCrossContextDispatch() throws Exception
    {
        String contextA = "/contextA";
        String contextB = "/contextB";
        String servletMapping = "/server";
        AbstractTestServer server = createServer(0, AbstractTestServer.DEFAULT_MAX_INACTIVE,  AbstractTestServer.DEFAULT_SCAVENGE_SEC,  AbstractTestServer.DEFAULT_EVICTIONPOLICY);
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

                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                // Perform a request to contextB with the same session cookie
                Request request = client.newRequest("http://localhost:" + port + contextB + servletMapping);
                request.header("Cookie", sessionCookie);
                ContentResponse responseB = request.send();
                assertEquals(HttpServletResponse.SC_OK,responseB.getStatus());
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
