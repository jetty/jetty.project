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

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Test;

/**
 * AbstractServerCrossContextSessionTest
 */
public abstract class AbstractServerCrossContextSessionTest extends AbstractTestBase
{

    @Test
    public void testCrossContextDispatch() throws Exception
    {
        String contextA = "/contextA";
        String contextB = "/contextB";
        String servletMapping = "/server";
        AbstractTestServer server = createServer(0, AbstractTestServer.DEFAULT_MAX_INACTIVE,  AbstractTestServer.DEFAULT_SCAVENGE_SEC,  AbstractTestServer.DEFAULT_EVICTIONPOLICY);
        ServletContextHandler ctxA = server.addContext(contextA);
        ctxA.addServlet(TestServletA.class, servletMapping);
        ServletContextHandler ctxB = server.addContext(contextB);
        ctxB.addServlet(TestServletB.class, servletMapping);
        try
        {
            server.start();
            int port=server.getPort();

            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // Perform a request, on server side a cross context dispatch will be done
                ContentResponse response = client.GET("http://localhost:" + port + contextA + servletMapping);
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

    public static class TestServletA extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            HttpSession session = request.getSession(false);
            if (session == null) session = request.getSession(true);
            // Add something to the session
            session.setAttribute("A", "A");
            // Perform cross context dispatch to another context
            // Over there we will check that the session attribute added above is not visible
            ServletContext contextB = getServletContext().getContext("/contextB");
            RequestDispatcher dispatcherB = contextB.getRequestDispatcher(request.getServletPath());
            dispatcherB.forward(request, response);

            // Check that we don't see things put in session by contextB
            Object objectB = session.getAttribute("B");
            assertTrue(objectB == null);
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
            assertTrue(objectA == null);

            // Add something, so in contextA we can check if it is visible (it must not).
            session.setAttribute("B", "B");
        }
    }
}
