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
 * AbstractImmortalSessionTest
 */
public abstract class AbstractImmortalSessionTest
{
    public abstract AbstractTestServer createServer(int port, int maxInactiveMs, int scavengeMs);

    @Test
    public void testImmortalSession() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int scavengePeriod = 2;
        //turn off session expiry by setting maxInactiveInterval to -1
        AbstractTestServer server = createServer(0, -1, scavengePeriod);
        server.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
        server.start();
        int port=server.getPort();
        
        try
        {
            HttpClient client = new HttpClient();
            client.setConnectorType(HttpClient.CONNECTOR_SOCKET);
            client.start();
            try
            {
                int value = 42;
                ContentExchange exchange = new ContentExchange(true);
                exchange.setMethod(HttpMethods.GET);
                exchange.setURL("http://localhost:" + port + contextPath + servletMapping + "?action=set&value=" + value);
                client.send(exchange);
                exchange.waitForDone();
                assertEquals(HttpServletResponse.SC_OK,exchange.getResponseStatus());
                String sessionCookie = exchange.getResponseFields().getStringField("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                String response = exchange.getResponseContent();
                assertEquals(response.trim(),String.valueOf(value));

                // Let's wait for the scavenger to run, waiting 2.5 times the scavenger period
                Thread.sleep(scavengePeriod * 2500L);

                // Be sure the session is still there
                exchange = new ContentExchange(true);
                exchange.setMethod(HttpMethods.GET);
                exchange.setURL("http://localhost:" + port + contextPath + servletMapping + "?action=get");
                exchange.getRequestFields().add("Cookie", sessionCookie);
                client.send(exchange);
                exchange.waitForDone();
                assertEquals(HttpServletResponse.SC_OK,exchange.getResponseStatus());
                response = exchange.getResponseContent();
                assertEquals(String.valueOf(value),response.trim());
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
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String result = null;
            String action = request.getParameter("action");
            if ("set".equals(action))
            {
                String value = request.getParameter("value");
                HttpSession session = request.getSession(true);
                session.setAttribute("value", value);
                result = value;
            }
            else if ("get".equals(action))
            {
                HttpSession session = request.getSession(false);
                if (session!=null)
                    result = (String)session.getAttribute("value");
            }
            PrintWriter writer = response.getWriter();
            writer.println(result);
            writer.flush();
        }
    }
}
