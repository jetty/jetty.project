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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Ignore;
import org.junit.Test;

import junit.framework.Assert;

/**
 * AbstractSessionCookieTest
 */
public abstract class AbstractSessionCookieTest extends AbstractTestBase
{

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
    @Ignore("failing because an http cookie with null value is coming over as \"null\"")
    public void testSessionCookie() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int scavengePeriod = 3;
        AbstractTestServer server = createServer(0, 1, scavengePeriod,SessionCache.NEVER_EVICT);
        ServletContextHandler context = server.addContext(contextPath);
        context.addServlet(TestServlet.class, servletMapping);

        try
        {
            server.start();
            int port=server.getPort();
            
            HttpClient client = new HttpClient();
            client.start();
            try
            {

                ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=create");
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());

                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                //sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                // Let's wait for the scavenger to run, waiting 2.5 times the scavenger period
                //pause(scavengePeriod);
                Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=check-cookie");
                request.header("Cookie", sessionCookie);
                response = request.send();

                assertEquals(HttpServletResponse.SC_OK,response.getStatus());

                request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=null-cookie");
                request.header("Cookie", sessionCookie);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
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
            else if ("check-cookie".equals(action))
            {
                HttpSession session = request.getSession(false);

                assertTrue(session != null);

                //request.getSession(true);
            }
            else if ("null-cookie".equals(action))
            {
                HttpSession session = request.getSession(false);

                assertEquals(1, request.getCookies().length);

                Assert.assertFalse("null".equals(request.getCookies()[0].getValue()));

                assertTrue(session == null);

            }
            else
            {
                assertTrue(false);
            }
        }
    }
}
