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
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Test;

public abstract class AbstractSessionExpiryTest
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
    public void testSessionNotExpired() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 10;
        int scavengePeriod = 10;
        AbstractTestServer server1 = createServer(0, inactivePeriod, scavengePeriod);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        server1.addContext(contextPath).addServlet(holder, servletMapping);

        HttpClient client = new HttpClient();
        try
        {
            server1.start();
            int port1 = server1.getPort();

            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK,response.getStatus());
            String sessionCookie = response.getHeaders().getStringField("Set-Cookie");
            assertTrue(sessionCookie != null);
            // Mangle the cookie, replacing Path with $Path, etc.
            sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

            //now stop the server
            server1.stop();

            //start the server again, before the session times out
            server1.start();
            port1 = server1.getPort();
            url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make another request, the session should not have expired
            Request request = client.newRequest(url + "?action=notexpired");
            request.getHeaders().add("Cookie", sessionCookie);
            ContentResponse response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());

        }
        finally
        {
            client.stop();
            server1.stop();
        }
    }

    @Test
    public void testSessionExpiry() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 2;
        int scavengePeriod = 10;
        AbstractTestServer server1 = createServer(0, inactivePeriod, scavengePeriod);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        server1.addContext(contextPath).addServlet(holder, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            ContentResponse response1 = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK,response1.getStatus());
            String sessionCookie = response1.getHeaders().getStringField("Set-Cookie");
            assertTrue(sessionCookie != null);
            // Mangle the cookie, replacing Path with $Path, etc.
            sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

            //now stop the server
            server1.stop();

            //and wait until the expiry time has passed
            pause(inactivePeriod);

            //restart the server
            server1.start();
            port1 = server1.getPort();
            url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make another request, the session should have expired
            Request request = client.newRequest(url + "?action=test");
            request.getHeaders().add("Cookie", sessionCookie);
            ContentResponse response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
        }
        finally
        {
            server1.stop();
        }
    }

    public static class TestServlet extends HttpServlet
    {
        public String originalId = null;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("test", "test");
                originalId = session.getId();
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(true);
                assertTrue(session != null);
                assertTrue(!originalId.equals(session.getId()));
            }
            else if ("notexpired".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session != null);
                assertTrue(originalId.equals(session.getId()));
            }

        }
    }
}
