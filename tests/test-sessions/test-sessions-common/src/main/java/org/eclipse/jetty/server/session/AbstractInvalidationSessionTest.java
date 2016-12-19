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
import static org.junit.Assert.fail;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.Test;

/**
 * AbstractInvalidationSessionTest
 * 
 * Goal of the test is to be sure that invalidating a session on one node
 * result in the session being unavailable in the other node also. This
 * simulates an environment without a sticky load balancer. In this case,
 * you must use session eviction, to try to ensure that as the session 
 * bounces around it gets a fresh load of data from the SessionDataStore.
 */
public abstract class AbstractInvalidationSessionTest extends AbstractTestBase
{


    @Test
    public void testInvalidation() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int maxInactiveInterval = 30;
        int scavengeInterval = 1;
        AbstractTestServer server1 = createServer(0, maxInactiveInterval, scavengeInterval, SessionCache.EVICT_ON_SESSION_EXIT);
        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);


        try
        {
            server1.start();
            int port1 = server1.getPort();
            AbstractTestServer server2 = createServer(0, maxInactiveInterval, scavengeInterval, SessionCache.EVICT_ON_SESSION_EXIT);
            server2.addContext(contextPath).addServlet(TestServlet.class, servletMapping);

            try
            {
                server2.start();
                int port2=server2.getPort();
                HttpClient client = new HttpClient();
                QueuedThreadPool executor = new QueuedThreadPool();
                client.setExecutor(executor);
                client.start();
                
                try
                {
                    String[] urls = new String[2];
                    urls[0] = "http://localhost:" + port1 + contextPath + servletMapping;
                    urls[1] = "http://localhost:" + port2 + contextPath + servletMapping;

                    // Create the session on node1
                    ContentResponse response1 = client.GET(urls[0] + "?action=init");

                    assertEquals(HttpServletResponse.SC_OK,response1.getStatus());
                    String sessionCookie = response1.getHeaders().get("Set-Cookie");
                    assertTrue(sessionCookie != null);
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                    // Be sure the session is also present in node2
                    Request request2 = client.newRequest(urls[1] + "?action=increment");
                    request2.header("Cookie", sessionCookie);
                    ContentResponse response2 = request2.send();
                    assertEquals(HttpServletResponse.SC_OK,response2.getStatus());

                    // Invalidate on node1
                    Request request1 = client.newRequest(urls[0] + "?action=invalidate");
                    request1.header("Cookie", sessionCookie);
                    response1 = request1.send();
                    assertEquals(HttpServletResponse.SC_OK, response1.getStatus());         

                    // Be sure on node2 we don't see the session anymore
                    request2 = client.newRequest(urls[1] + "?action=test");
                    request2.header("Cookie", sessionCookie);
                    response2 = request2.send();
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
                session.setAttribute("value", 0);
            }
            else if ("increment".equals(action))
            {
                HttpSession session = request.getSession(false);
                int value = (Integer)session.getAttribute("value");
                session.setAttribute("value", value + 1);
            }
            else if ("invalidate".equals(action))
            {
                HttpSession session = request.getSession(false);
                session.invalidate();
                
                try
                {
                    session.invalidate();
                    fail("Session should be invalid");
                    
                }
                catch (IllegalStateException e)
                {
                    //expected
                }
                
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertEquals(null,session);
            }
        }
    }
}
