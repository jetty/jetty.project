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
import java.io.PrintWriter;

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
 * AbstractSessionMigrationTest
 * 
 * Check that a session that is active on node 1 can be accessed on node2.
 */
public abstract class AbstractSessionMigrationTest extends AbstractTestBase
{


    @Test
    public void testSessionMigration() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        AbstractTestServer server1 = createServer(0, AbstractTestServer.DEFAULT_MAX_INACTIVE,  AbstractTestServer.DEFAULT_SCAVENGE_SEC,  AbstractTestServer.DEFAULT_EVICTIONPOLICY);
        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);

        try
        {
            server1.start();
            int port1=server1.getPort();
            
            AbstractTestServer server2 = createServer(0, AbstractTestServer.DEFAULT_MAX_INACTIVE,  AbstractTestServer.DEFAULT_SCAVENGE_SEC,  AbstractTestServer.DEFAULT_EVICTIONPOLICY);
            server2.addContext(contextPath).addServlet(TestServlet.class, servletMapping);

            try
            {
                server2.start();
                int port2=server2.getPort();
                
                HttpClient client = new HttpClient();
                client.start();
                try
                {
                    // Perform one request to server1 to create a session
                    int value = 1;
                    Request request1 = client.POST("http://localhost:" + port1 + contextPath + servletMapping + "?action=set&value=" + value);
                    ContentResponse response1 = request1.send();
                    assertEquals(HttpServletResponse.SC_OK,response1.getStatus());
                    String sessionCookie = response1.getHeaders().get("Set-Cookie");
                    assertTrue(sessionCookie != null);
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                    // Perform a request to server2 using the session cookie from the previous request
                    // This should migrate the session from server1 to server2.
                    Request request2 = client.newRequest("http://localhost:" + port2 + contextPath + servletMapping + "?action=get");
                    request2.header("Cookie", sessionCookie);
                    ContentResponse response2 = request2.send();
                    assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
                    String response = response2.getContentAsString();
                    assertEquals(response.trim(),String.valueOf(value));               }
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
            doPost(request, response);
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            HttpSession session = request.getSession(false);

            String action = request.getParameter("action");
            if ("set".equals(action))
            {
                if (session == null) session = request.getSession(true);
                int value = Integer.parseInt(request.getParameter("value"));
                session.setAttribute("value", value);
                PrintWriter writer = response.getWriter();
                writer.println(value);
                writer.flush();
            }
            else if ("get".equals(action))
            {
                int value = (Integer)session.getAttribute("value");
                int x = session.getMaxInactiveInterval();
                assertTrue(x > 0);
                PrintWriter writer = response.getWriter();
                writer.println(value);
                writer.flush();
            }
        }
    }
}
