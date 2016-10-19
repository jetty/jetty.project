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
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Test;

/**
 * AbstractOrphanedSessionTest
 */
public abstract class AbstractOrphanedSessionTest extends AbstractTestBase
{

    /**
     * If nodeA creates a session, and just afterwards crashes, it is the only node that knows about the session.
     * We want to test that the session data is gone after scavenging.
     * @throws Exception on test failure
     */
    @Test
    public void testOrphanedSession() throws Exception
    {
        // Disable scavenging for the first server, so that we simulate its "crash".
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 5;
        AbstractTestServer server1 = createServer(0, inactivePeriod, -1, SessionCache.NEVER_EVICT);
        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
        try
        {
            server1.start();
            int port1 = server1.getPort();
            int scavengePeriod = 2;
            AbstractTestServer server2 = createServer(0, inactivePeriod, scavengePeriod, 2);
            server2.addContext(contextPath).addServlet(TestServlet.class, servletMapping);         
            try
            {
                server2.start();
                int port2 = server2.getPort();
                HttpClient client = new HttpClient();
                client.start();
                try
                {
                    // Connect to server1 to create a session and get its session cookie
                    ContentResponse response1 = client.GET("http://localhost:" + port1 + contextPath + servletMapping + "?action=init");
                    assertEquals(HttpServletResponse.SC_OK,response1.getStatus());
                    String sessionCookie = response1.getHeaders().get("Set-Cookie");
                    assertTrue(sessionCookie != null);
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                    // Wait for the session to expire.
                    // The first node does not do any scavenging, but the session
                    // must be removed by scavenging done in the other node.
                    Thread.sleep(TimeUnit.SECONDS.toMillis(inactivePeriod + 2L * scavengePeriod));

                    // Perform one request to server2 to be sure that the session has been expired
                    Request request = client.newRequest("http://localhost:" + port2 + contextPath + servletMapping + "?action=check");
                    request.header("Cookie", sessionCookie);
                    ContentResponse response2 = request.send();
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
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("A", "A");
            }
            else if ("remove".equals(action))
            {
                HttpSession session = request.getSession(false);
                session.invalidate();
                //assertTrue(session == null);
            }
            else if ("check".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session == null);
            }
        }
    }
}
