//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
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
 * AbstractInvalidationSessionTest
 * Goal of the test is to be sure that invalidating a session on one node
 * result in the session being unavailable in the other node also.
 */
public abstract class AbstractInvalidationSessionTest
{
    public abstract AbstractTestServer createServer(int port);
    public abstract void pause();

    @Test
    public void testInvalidation() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        AbstractTestServer server1 = createServer(0);
        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
        server1.start();
        int port1 = server1.getPort();
        try
        {
            AbstractTestServer server2 = createServer(0);
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
                    String[] urls = new String[2];
                    urls[0] = "http://localhost:" + port1 + contextPath + servletMapping;
                    urls[1] = "http://localhost:" + port2 + contextPath + servletMapping;

                    // Create the session on node1
                    ContentExchange exchange1 = new ContentExchange(true);
                    exchange1.setMethod(HttpMethods.GET);
                    exchange1.setURL(urls[0] + "?action=init");
                    client.send(exchange1);
                    exchange1.waitForDone();
                    assertEquals(HttpServletResponse.SC_OK,exchange1.getResponseStatus());
                    String sessionCookie = exchange1.getResponseFields().getStringField("Set-Cookie");
                    assertTrue(sessionCookie != null);
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                    // Be sure the session is also present in node2
                    ContentExchange exchange2 = new ContentExchange(true);
                    exchange2.setMethod(HttpMethods.GET);
                    exchange2.setURL(urls[1] + "?action=increment");
                    exchange2.getRequestFields().add("Cookie", sessionCookie);
                    client.send(exchange2);
                    exchange2.waitForDone();
                    assertEquals(HttpServletResponse.SC_OK,exchange2.getResponseStatus());

                    // Invalidate on node1
                    exchange1 = new ContentExchange(true);
                    exchange1.setMethod(HttpMethods.GET);
                    exchange1.setURL(urls[0] + "?action=invalidate");
                    exchange1.getRequestFields().add("Cookie", sessionCookie);
                    client.send(exchange1);
                    exchange1.waitForDone();
                    assertEquals(HttpServletResponse.SC_OK, exchange1.getResponseStatus());

                    pause();
                    
                    // Be sure on node2 we don't see the session anymore
                    exchange2 = new ContentExchange(true);
                    exchange2.setMethod(HttpMethods.GET);
                    exchange2.setURL(urls[1] + "?action=test");
                    exchange2.getRequestFields().add("Cookie", sessionCookie);
                    client.send(exchange2);
                    exchange2.waitForDone();
                    assertEquals(HttpServletResponse.SC_OK,exchange2.getResponseStatus());
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
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertEquals(null,session);
            }
        }
    }
}
