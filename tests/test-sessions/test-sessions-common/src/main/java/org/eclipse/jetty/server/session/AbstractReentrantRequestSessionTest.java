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
import org.junit.Test;


/**
 * AbstractReentrantRequestSessionTest
 */
public abstract class AbstractReentrantRequestSessionTest
{
    public abstract AbstractTestServer createServer(int port);

    @Test
    public void testReentrantRequestSession() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        AbstractTestServer server = createServer(0);
        server.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
        try
        {
            server.start();
            int port = server.getPort();

            HttpClient client = new HttpClient();
            client.start();
            try
            {
                ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=reenter&port=" + port + "&path=" + contextPath + servletMapping);
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
            doPost(request, response);
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            HttpSession session = request.getSession(false);

            String action = request.getParameter("action");
            if ("reenter".equals(action))
            {
                if (session == null)
                    session = request.getSession(true);
                int port = Integer.parseInt(request.getParameter("port"));
                String path = request.getParameter("path");

                // We want to make another request
                // while this request is still pending, to see if the locking is
                // fine grained (per session at least).
                try
                {
                    HttpClient client = new HttpClient();
                    client.start();
                    try
                    {
                        ContentResponse resp = client.GET("http://localhost:" + port + path + ";jsessionid="+session.getId()+"?action=none");
                        assertEquals(HttpServletResponse.SC_OK,resp.getStatus());
                        assertEquals("true",session.getAttribute("reentrant"));
                    }
                    finally
                    {
                        client.stop();
                    }
                }
                catch (Exception x)
                {
                    throw new ServletException(x);
                }
            }
            else
            {
                assertTrue(session!=null);
                session.setAttribute("reentrant","true");
            }
        }
    }
}
