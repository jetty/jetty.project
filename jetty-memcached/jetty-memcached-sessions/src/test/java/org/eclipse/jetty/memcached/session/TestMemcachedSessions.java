//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.memcached.session;

import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.session.CachingSessionDataStore;
import org.eclipse.jetty.server.session.NullSessionCache;
import org.eclipse.jetty.server.session.NullSessionDataStore;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * TestMemcachedSessions
 */
public class TestMemcachedSessions
{
    public static class TestServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            String arg = req.getParameter("action");
            if (arg == null)
                return;
            HttpSession s = null;
            if ("set".equals(arg))
            {
                s = req.getSession(true);
                assertNotNull(s);
                s.setAttribute("val", req.getParameter("value"));
            }
            else if ("get".equals(arg))
            {
                s = req.getSession(false);
            }
            else if ("del".equals(arg))
            {
                s = req.getSession();
                assertNotNull(s);
                s.invalidate();
                s = null;
            }

            resp.setContentType("text/html");
            PrintWriter w = resp.getWriter();
            if (s == null)
                w.write("No session");
            else
                w.write((String)s.getAttribute("val"));
        }
    }

    @Test
    public void testMemcached() throws Exception
    {
        String contextPath = "/";
        Server server = new Server(0);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.setResourceBase(System.getProperty("java.io.tmpdir"));
        server.setHandler(context);
        NullSessionCache dsc = new NullSessionCache(context.getSessionHandler());
        dsc.setSessionDataStore(new CachingSessionDataStore(new MemcachedSessionDataMap("localhost", "11211"), new NullSessionDataStore()));
        context.getSessionHandler().setSessionCache(dsc);

        // Add a test servlet
        ServletHolder h = new ServletHolder();
        h.setServlet(new TestServlet());
        context.addServlet(h, "/");

        try
        {
            server.start();
            int port = ((NetworkConnector)server.getConnectors()[0]).getLocalPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {

                int value = 42;
                ContentResponse response = client.GET("http://localhost:" + port + contextPath + "?action=set&value=" + value);
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertNotNull(sessionCookie);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)([Pp])ath=", "$1\\$Path=");

                String resp = response.getContentAsString();
                assertEquals(resp.trim(), String.valueOf(value));

                // Be sure the session value is still there
                HttpField cookie = new HttpField("Cookie", sessionCookie);
                Request request = client.newRequest("http://localhost:" + port + contextPath + "?action=get");
                request.headers(headers -> headers.put(cookie));
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());

                resp = response.getContentAsString();
                assertEquals(String.valueOf(value), resp.trim());

                //Delete the session
                request = client.newRequest("http://localhost:" + port + contextPath + "?action=del");
                request.headers(headers -> headers.put(cookie));
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());

                //Check that the session is gone
                request = client.newRequest("http://localhost:" + port + contextPath + "?action=get");
                request.headers(headers -> headers.put(cookie));
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                resp = response.getContentAsString();
                assertEquals("No session", resp.trim());
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
}
