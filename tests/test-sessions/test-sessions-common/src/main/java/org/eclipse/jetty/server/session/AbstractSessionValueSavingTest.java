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
 * AbstractSessionValueSavingTest
 */
public abstract class AbstractSessionValueSavingTest extends AbstractTestBase
{

    @Test
    public void testSessionValueSaving() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int maxInactivePeriod = 10000;
        int scavengePeriod = 20000;
        AbstractTestServer server1 = createServer(0, maxInactivePeriod, scavengePeriod, SessionCache.NEVER_EVICT);
        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
 
        try
        {
            server1.start();
            int port1=server1.getPort();
            
                HttpClient client = new HttpClient();
                client.start();
                try
                {
                    long sessionTestValue = 0;

                    // Perform one request to server1 to create a session
                    ContentResponse response1 = client.GET("http://localhost:" + port1 + contextPath + servletMapping + "?action=init");

                    assertEquals(HttpServletResponse.SC_OK, response1.getStatus());
                    assertTrue(sessionTestValue < Long.parseLong(response1.getContentAsString()));

                    sessionTestValue = Long.parseLong(response1.getContentAsString());

                    String sessionCookie = response1.getHeaders().get("Set-Cookie");
                    assertTrue( sessionCookie != null );
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                    // We want to test that optimizations done to the saving of the shared lastAccessTime
                    // do not break the correct working
                    int requestInterval = 500;


                    for (int i = 0; i < 10; ++i)
                    {
                        Request request2 = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping);
                        request2.header("Cookie", sessionCookie);
                        ContentResponse response2 = request2.send();

                        assertEquals(HttpServletResponse.SC_OK , response2.getStatus());
                        assertTrue(sessionTestValue < Long.parseLong(response2.getContentAsString()));
                        sessionTestValue = Long.parseLong(response2.getContentAsString());

                        String setCookie = response1.getHeaders().get("Set-Cookie");
                        if (setCookie!=null)
                            sessionCookie = setCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                        Thread.sleep(requestInterval);
                    }

                }
                finally
                {
                    client.stop();
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
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("test", System.currentTimeMillis());

                sendResult(session, httpServletResponse.getWriter());
            }
            else
            {
                HttpSession session = request.getSession(false);
                if (session!=null)
                {
                    long value = System.currentTimeMillis();
                    session.setAttribute("test", value);
                }

                sendResult(session, httpServletResponse.getWriter());

            }


        }

        private void sendResult(HttpSession session, PrintWriter writer)
        {
                if (session != null)
                {
                        writer.print(session.getAttribute("test"));
                }
                else
                {
                        writer.print(0);
                }
        }

    }
}
