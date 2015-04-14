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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CreateInForwardedRequestTest
{
    public static final String TEST_CONTEXT_PATH = "/testContext";
    public static final String TEST_FORWARD_SERVLET_PATH = "/forward";
    public static final String TEST_FORWARD_HANDLER_SERVLET_PATH = "/forwardHandler";
    public static final String RESPONSE_BODY_VALUE = "Success From TestForwardHandlerServlet";
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

        try
        {
            testServer.start();
            int serverPort=testServer.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                ContentResponse response = client.GET("http://localhost:" + serverPort + TEST_CONTEXT_PATH + TEST_FORWARD_SERVLET_PATH);
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                assertEquals(RESPONSE_BODY_VALUE, response.getContentAsString());
                assertNotNull(capturedSession.get());
                assertEquals(NoSqlSession.class, capturedSession.get().getClass());
                assertEquals(0, ((NoSqlSession) capturedSession.get()).getActive());
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
            HttpSession session = request.getSession();
            capturedSession.set(session);
            session.setAttribute("TEST_ATTRIBUTE", "TEST_ATTRIBUTE_VALUE");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(RESPONSE_BODY_VALUE);
        }
    }
}
