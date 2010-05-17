// ========================================================================
// Copyright 2004-2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.server.session;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.testng.annotations.Test;

/**
 * AbstractOrphanedSessionTest
 *
 *
 */

public abstract class AbstractOrphanedSessionTest
{

    public abstract AbstractTestServer createServer(int port, int max, int scavenge);

    /**
     * If nodeA creates a session, and just afterwards crashes, it is the only node that knows about the session.
     * We want to test that the session data is gone after scavenging.
     */
    @Test
    public void testOrphanedSession() throws Exception
    {
        Random random = new Random(System.nanoTime());

        // Disable scavenging for the first server, so that we simulate its "crash".
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 5;
        AbstractTestServer server1 = createServer(0, inactivePeriod, -1);
        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
        server1.start();
        int port1 = server1.getPort();
        try
        {
            int scavengePeriod = 2;
            AbstractTestServer server2 = createServer(0, inactivePeriod, scavengePeriod);
            server2.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
            server2.start();
            int port2 = server2.getPort();
            try
            {
                HttpClient client = new HttpClient();
                client.setConnectorType(HttpClient.CONNECTOR_SOCKET);
                client.start();
                try
                {
                    // Connect to server1 to create a session and get its session cookie
                    ContentExchange exchange1 = new ContentExchange(true);
                    exchange1.setMethod(HttpMethods.GET);
                    exchange1.setURL("http://localhost:" + port1 + contextPath + servletMapping + "?action=init");
                    client.send(exchange1);
                    exchange1.waitForDone();
                    assert exchange1.getResponseStatus() == HttpServletResponse.SC_OK;
                    String sessionCookie = exchange1.getResponseFields().getStringField("Set-Cookie");
                    assert sessionCookie != null;
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                    // Wait for the session to expire.
                    // The first node does not do any scavenging, but the session
                    // must be removed by scavenging done in the other node.
                    Thread.sleep(TimeUnit.SECONDS.toMillis(inactivePeriod + 2L * scavengePeriod));

                    System.err.println("FINISHED waiting for session to expire");
                    // Perform one request to server2 to be sure that the session has been expired
                    
                    System.err.println("CHECKING NODE2");
                    ContentExchange exchange2 = new ContentExchange(true);
                    exchange2.setMethod(HttpMethods.GET);
                    exchange2.setURL("http://localhost:" + port2 + contextPath + servletMapping + "?action=check");
                    exchange2.getRequestFields().add("Cookie", sessionCookie);
                    client.send(exchange2);
                    exchange2.waitForDone();
                    assert exchange2.getResponseStatus() == HttpServletResponse.SC_OK;
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
            else if ("check".equals(action))
            {
                HttpSession session = request.getSession(false);
                assert session == null;
            }
        }
    }
}
