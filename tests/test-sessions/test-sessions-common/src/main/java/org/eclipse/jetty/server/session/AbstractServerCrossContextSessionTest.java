// ========================================================================
// Copyright 2004-2008 Mort Bay Consulting Pty. Ltd.
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
import java.util.Collections;
import java.util.Random;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.testng.annotations.Test;

/**
 * AbstractServerCrossContextSessionTest
 *
 *
 */

public abstract class AbstractServerCrossContextSessionTest
{

    public abstract AbstractTestServer createServer(int port);
    
    @Test
    public void testCrossContextDispatch() throws Exception
    {
        Random random = new Random(System.nanoTime());

        String contextA = "/contextA";
        String contextB = "/contextB";
        String servletMapping = "/server";
        int port = random.nextInt(50000) + 10000;
        AbstractTestServer server = createServer(port);
        ServletContextHandler ctxA = server.addContext(contextA);
        ctxA.addServlet(TestServletA.class, servletMapping);
        ServletContextHandler ctxB = server.addContext(contextB);
        ctxB.addServlet(TestServletB.class, servletMapping);
        server.start();
        try
        {
            HttpClient client = new HttpClient();
            client.setConnectorType(HttpClient.CONNECTOR_SOCKET);
            client.start();
            try
            {
                // Perform a request, on server side a cross context dispatch will be done
                ContentExchange exchange = new ContentExchange(true);
                exchange.setMethod(HttpMethods.GET);
                exchange.setURL("http://localhost:" + port + contextA + servletMapping);
                client.send(exchange);
                exchange.waitForDone();
                assert exchange.getResponseStatus() == HttpServletResponse.SC_OK;
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

    public static class TestServletA extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            HttpSession session = request.getSession(false);
            if (session == null) session = request.getSession(true);

            // Add something to the session
            session.setAttribute("A", "A");
            System.out.println("A: session.getAttributeNames() = " + Collections.list(session.getAttributeNames()));

            // Perform cross context dispatch to another context
            // Over there we will check that the session attribute added above is not visible
            ServletContext contextB = getServletContext().getContext("/contextB");
            RequestDispatcher dispatcherB = contextB.getRequestDispatcher(request.getServletPath());
            dispatcherB.forward(request, response);

            // Check that we don't see things put in session by contextB
            Object objectB = session.getAttribute("B");
            assert objectB == null;
            System.out.println("A: session.getAttributeNames() = " + Collections.list(session.getAttributeNames()));
        }
    }

    public static class TestServletB extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            HttpSession session = request.getSession(false);
            if (session == null) session = request.getSession(true);

            // Be sure nothing from contextA is present
            Object objectA = session.getAttribute("A");
            assert objectA == null;

            // Add something, so in contextA we can check if it is visible (it must not).
            session.setAttribute("B", "B");
            System.out.println("B: session.getAttributeNames() = " + Collections.list(session.getAttributeNames()));
        }
    }
}
