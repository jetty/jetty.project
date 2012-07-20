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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * AbstractNewSessionTest
 */
public abstract class AbstractNewSessionTest
{
    public abstract AbstractTestServer createServer(int port, int max, int scavenge);

    public void pause(int scavenge)
    {
        try
        {
            Thread.sleep(scavenge * 2500L);
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }
    
    @Test
    public void testNewSession() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int scavengePeriod = 3;
        AbstractTestServer server = createServer(0, 1, scavengePeriod);
        ServletContextHandler context = server.addContext(contextPath);
        context.addServlet(TestServlet.class, servletMapping);
        server.start();
        int port=server.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.setConnectorType(HttpClient.CONNECTOR_SOCKET);
            client.start();
            try
            {
                ContentExchange exchange = new ContentExchange(true);
                exchange.setMethod(HttpMethods.GET);
                exchange.setURL("http://localhost:" + port + contextPath + servletMapping + "?action=create");
                client.send(exchange);
                exchange.waitForDone();
                assertEquals(HttpServletResponse.SC_OK,exchange.getResponseStatus());
                String sessionCookie = exchange.getResponseFields().getStringField("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                // Let's wait for the scavenger to run, waiting 2.5 times the scavenger period
                pause(scavengePeriod);

                // The session is not there anymore, but we present an old cookie
                // The server creates a new session, we must ensure we released all locks
                exchange = new ContentExchange(true);
                exchange.setMethod(HttpMethods.GET);
                exchange.setURL("http://localhost:" + port + contextPath + servletMapping + "?action=old-create");
                exchange.getRequestFields().add("Cookie", sessionCookie);
                client.send(exchange);
                exchange.waitForDone();
                assertEquals(HttpServletResponse.SC_OK,exchange.getResponseStatus());
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
            String action = request.getParameter("action");
            if ("create".equals(action))
            {
                HttpSession session = request.getSession(true);
                assertTrue(session.isNew());
            }
            else if ("old-create".equals(action))
            {
                request.getSession(true);
            }
            else
            {
                assertTrue(false);
            }
        }
    }
}
