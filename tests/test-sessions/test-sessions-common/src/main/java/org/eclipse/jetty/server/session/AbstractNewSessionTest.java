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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
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
import org.junit.Test;

/**
 * AbstractNewSessionTest
 * 
 * Create a session, wait for it to be scavenged, re-present the cookie and check that  a
 * new session is created.
 */
public abstract class AbstractNewSessionTest extends AbstractTestBase
{

    public void pause(int scavenge)
    {
        try
        {
            Thread.sleep(scavenge * 1000L);
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    @Test
    public void testNewSession() throws Exception
    {
        String servletMapping = "/server";
        int scavengePeriod = 3;
        int maxInactivePeriod = 1;
        AbstractTestServer server = createServer(0, maxInactivePeriod, scavengePeriod, SessionCache.NEVER_EVICT);
        ServletContextHandler context = server.addContext("/");
        context.addServlet(TestServlet.class, servletMapping);
        String contextPath = "";

        try
        {
            server.start();
            int port=server.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=create");
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                // Let's wait for the scavenger to run
                pause(maxInactivePeriod + scavengePeriod);

                // The session should not be there anymore, but we present an old cookie
                // The server should create a new session.
                Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=old-create");
                request.header("Cookie", sessionCookie);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
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
        String id;
        
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("create".equals(action))
            {
                HttpSession session = request.getSession(true);
                assertTrue(session.isNew());
                id = session.getId();
            }
            else if ("old-create".equals(action))
            {
                HttpSession s = request.getSession(false);
                assertNull(s);
                s = request.getSession(true);
                assertNotNull(s);
                assertFalse(s.getId().equals(id));
            }
            else
            {
                assertTrue(false);
            }
        }
    }
}
