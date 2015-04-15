//
//  ========================================================================
//  Copyright (c) 2015 Epic Games Inc.
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

package org.eclipse.jetty.nosql.mongodb;


import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.nosql.NoSqlSession;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Test;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CreateInForwardedRequestTest
{
    public static final String TEST_CONTEXT_PATH = "/testContext";
    public static final String TEST_FORWARD_SERVLET_PATH = "/forward";
    public static final String TEST_FORWARD_HANDLER_SERVLET_PATH = "/forwardHandler";
    private static AtomicReference<HttpSession> capturedSession = new AtomicReference<>();

    public AbstractTestServer createServer(int port)
    {
        return new MongoTestServer(port);
    }

    @Test
    public void testCreationOfSessionInForward() throws Exception
    {
        AbstractTestServer testServer = createServer(0);
        ServletContextHandler testServletContextHandler = testServer.addContext(TEST_CONTEXT_PATH);
        testServletContextHandler.addServlet(TestForwardServlet.class, TEST_FORWARD_SERVLET_PATH);
        testServletContextHandler.addServlet(TestForwardHandlerServlet.class, TEST_FORWARD_HANDLER_SERVLET_PATH);

		AbstractTestServer testServer2 = createServer(0);
		ServletContextHandler testServletContextHandler2 = testServer2.addContext(TEST_CONTEXT_PATH);
		testServletContextHandler2.addServlet(TestForwardServlet.class, TEST_FORWARD_SERVLET_PATH);
		testServletContextHandler2.addServlet(TestForwardHandlerServlet.class, TEST_FORWARD_HANDLER_SERVLET_PATH);

        try
        {
            testServer.start();
			testServer2.start();
            int serverPort=testServer.getPort();
			int serverPort2=testServer2.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {
				// set the value on server 1
				String value = "TEST_VALUE";
                ContentResponse response = client.GET("http://localhost:" + serverPort + TEST_CONTEXT_PATH + TEST_FORWARD_SERVLET_PATH + "?action=set&value=" + value);
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                assertEquals(value, response.getContentAsString().trim());


				// use the same cookie to get the value on server 2, should be the same as server 1
				ContentResponse response2 = client.GET("http://localhost:" + serverPort2 + TEST_CONTEXT_PATH + TEST_FORWARD_SERVLET_PATH + "?action=get");
				assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
				assertEquals(value, response2.getContentAsString().trim());


				// now update the value on server 2
				String value2 = "TEST_VALUE2";
				ContentResponse response3 = client.GET("http://localhost:" + serverPort2 + TEST_CONTEXT_PATH + TEST_FORWARD_SERVLET_PATH + "?action=set&value=" + value2);
				assertEquals(HttpServletResponse.SC_OK, response3.getStatus());
				assertEquals(value2, response3.getContentAsString().trim());


				// now get the value on server 1 should be the same as server 2
				ContentResponse response4 = client.GET("http://localhost:" + serverPort + TEST_CONTEXT_PATH + TEST_FORWARD_SERVLET_PATH + "?action=get");
				assertEquals(HttpServletResponse.SC_OK, response4.getStatus());
				assertEquals(value2, response4.getContentAsString().trim());

            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            testServer.stop();
        }
    }

    public static class TestForwardServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            // forward to our other servlet
            RequestDispatcher dispatcher = request.getServletContext().getContext(TEST_CONTEXT_PATH)
                .getRequestDispatcher(TEST_FORWARD_HANDLER_SERVLET_PATH);
            dispatcher.forward(request, response);
        }
    }

    public static class TestForwardHandlerServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            HttpSession session = request.getSession(false);

			String action = request.getParameter("action");
			if ("set".equals(action))
			{
				if (session == null) session = request.getSession(true);
				session.setAttribute("value", request.getParameter("value"));
				PrintWriter writer = response.getWriter();
				writer.println(session.getAttribute("value"));
				writer.flush();
			}
			else if ("get".equals(action))
			{
				String value = (String) session.getAttribute("value");
				int x = session.getMaxInactiveInterval();
				assertTrue(x > 0);
				PrintWriter writer = response.getWriter();
				writer.println(value);
				writer.flush();
			}
        }
    }
}
