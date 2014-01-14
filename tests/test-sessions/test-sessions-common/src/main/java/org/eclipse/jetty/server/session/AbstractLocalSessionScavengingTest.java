//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SessionManager;
import org.junit.Test;

/**
 * AbstractLocalSessionScavengingTest
 */
public abstract class AbstractLocalSessionScavengingTest
{
    public abstract AbstractTestServer createServer(int port, int max, int scavenge);

    public void pause(int scavengePeriod)
    {
        try
        {
            Thread.sleep(scavengePeriod * 2500L);
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    @Test
    public void testLocalSessionsScavenging() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 1;
        int scavengePeriod = 2;
        AbstractTestServer server1 = createServer(0, inactivePeriod, scavengePeriod);
        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);

        try
        {
            server1.start();
            int port1 = server1.getPort();
            AbstractTestServer server2 = createServer(0, inactivePeriod, scavengePeriod * 3);
            server2.addContext(contextPath).addServlet(TestServlet.class, servletMapping);

            try
            {
                server2.start();
                int port2 = server2.getPort();
                HttpClient client = new HttpClient();
                client.start();
                try
                {
                    String[] urls = new String[2];
                    urls[0] = "http://localhost:" + port1 + contextPath + servletMapping;
                    urls[1] = "http://localhost:" + port2 + contextPath + servletMapping;

                    // Create the session on node1
                    ContentResponse response1 = client.GET(urls[0] + "?action=init");
                    assertEquals(HttpServletResponse.SC_OK,response1.getStatus());
                    String sessionCookie = response1.getHeaders().getStringField("Set-Cookie");
                    assertTrue(sessionCookie != null);
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                    // Be sure the session is also present in node2
                    org.eclipse.jetty.client.api.Request request = client.newRequest(urls[1] + "?action=test");
                    request.header("Cookie", sessionCookie);
                    ContentResponse response2 = request.send();
                    assertEquals(HttpServletResponse.SC_OK,response2.getStatus());


                    // Wait for the scavenger to run on node1, waiting 2.5 times the scavenger period
                    pause(scavengePeriod);

                    // Check that node1 does not have any local session cached
                    request = client.newRequest(urls[0] + "?action=check");
                    request.header("Cookie", sessionCookie);
                    response1 = request.send();
                    assertEquals(HttpServletResponse.SC_OK,response1.getStatus());


                    // Wait for the scavenger to run on node2, waiting 2 times the scavenger period
                    // This ensures that the scavenger on node2 runs at least once.
                    pause(scavengePeriod);

                    // Check that node2 does not have any local session cached
                    request = client.newRequest(urls[1] + "?action=check");
                    request.header("Cookie", sessionCookie);
                    response2 = request.send();
                    assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
                }
                finally
                {
                    client.stop();
                }
            }
            finally
            {
                server2.stop();
            }
        }
        finally
        {
            server1.stop();
        }
    }

    public static class TestServlet extends HttpServlet
    {
        private SessionManager sessionManager;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("test", "test");
                this.sessionManager = ((Request)request).getSessionManager();
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                session.setAttribute("test", "test");
                this.sessionManager = ((Request)request).getSessionManager();
            }
            else if ("check".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session == null);
            }
        }
    }
}
