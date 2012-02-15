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
import java.io.PrintWriter;
import java.util.Random;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpMethods;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


/**
 * AbstractLastAccessTimeTest
 */
public abstract class AbstractLastAccessTimeTest
{
    public abstract AbstractTestServer createServer(int port, int max, int scavenge);

    @Test
    public void testLastAccessTime() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int maxInactivePeriod = 8;
        int scavengePeriod = 2;
        AbstractTestServer server1 = createServer(0, maxInactivePeriod, scavengePeriod);
        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
        server1.start();
        int port1=server1.getPort();
        try
        {
            AbstractTestServer server2 = createServer(0, maxInactivePeriod, scavengePeriod);
            server2.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
            server2.start();
            int port2=server2.getPort();
            try
            {
                HttpClient client = new HttpClient();
                client.setConnectorType(HttpClient.CONNECTOR_SOCKET);
                client.start();
                try
                {
                    // Perform one request to server1 to create a session
                    ContentExchange exchange1 = new ContentExchange(true);
                    exchange1.setMethod(HttpMethods.GET);
                    exchange1.setURL("http://localhost:" + port1 + contextPath + servletMapping + "?action=init");
                    client.send(exchange1);
                    exchange1.waitForDone();
                    assertEquals(HttpServletResponse.SC_OK, exchange1.getResponseStatus());
                    assertEquals("test", exchange1.getResponseContent());
                    String sessionCookie = exchange1.getResponseFields().getStringField("Set-Cookie");
                    assertTrue( sessionCookie != null );
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                    // Perform some request to server2 using the session cookie from the previous request
                    // This should migrate the session from server1 to server2, and leave server1's
                    // session in a very stale state, while server2 has a very fresh session.
                    // We want to test that optimizations done to the saving of the shared lastAccessTime
                    // do not break the correct working
                    int requestInterval = 500;
                    for (int i = 0; i < maxInactivePeriod * (1000 / requestInterval); ++i)
                    {
                        ContentExchange exchange2 = new ContentExchange(true);
                        exchange2.setMethod(HttpMethods.GET);
                        exchange2.setURL("http://localhost:" + port2 + contextPath + servletMapping);
                        exchange2.getRequestFields().add("Cookie", sessionCookie);
                        client.send(exchange2);
                        exchange2.waitForDone();
                        assertEquals(HttpServletResponse.SC_OK , exchange2.getResponseStatus());
                        assertEquals("test", exchange2.getResponseContent());

                        String setCookie = exchange1.getResponseFields().getStringField("Set-Cookie");
                        if (setCookie!=null)                    
                            sessionCookie = setCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
                        
                        Thread.sleep(requestInterval);
                    }

                    // At this point, session1 should be eligible for expiration.
                    // Let's wait for the scavenger to run, waiting 2.5 times the scavenger period
                    Thread.sleep(scavengePeriod * 2500L);

                    // Access again server1, and ensure that we can still access the session
                    exchange1 = new ContentExchange(true);
                    exchange1.setMethod(HttpMethods.GET);
                    exchange1.setURL("http://localhost:" + port1 + contextPath + servletMapping);
                    exchange1.getRequestFields().add("Cookie", sessionCookie);
                    client.send(exchange1);
                    exchange1.waitForDone();
                    assertEquals(HttpServletResponse.SC_OK, exchange1.getResponseStatus());
                    //test that the session was kept alive by server 2 and still contains what server1 put in it
                    assertEquals("test", exchange1.getResponseContent());
                    
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
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("test", "test");
                
                sendResult(session, httpServletResponse.getWriter());

            }
            else
            {
                HttpSession session = request.getSession(false);

                // if we node hopped we should get the session and test should already be present
                sendResult(session, httpServletResponse.getWriter());

                if (session!=null)
                {                                       
                    session.setAttribute("test", "test");
                }
                

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
                        writer.print("null");
                }
        }
    }
}
