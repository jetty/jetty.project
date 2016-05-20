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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Test;

/**
 * AbstractRemoveSessionTest
 *
 * Test that invalidating a session does not return the session on the next request.
 * 
 */
public abstract class AbstractRemoveSessionTest extends AbstractTestBase
{


    @Test
    public void testRemoveSession() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        AbstractTestServer server = createServer(0, -1, -1, SessionCache.NEVER_EVICT);
        ServletContextHandler context = server.addContext(contextPath);
        context.addServlet(TestServlet.class, servletMapping);
        TestEventListener testListener = new TestEventListener();
        context.getSessionHandler().addEventListener(testListener);
        SessionHandler m = context.getSessionHandler();
        try
        {
            server.start();
            int port = server.getPort();

            HttpClient client = new HttpClient();
            client.start();
            try
            {
                ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=create");
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
                //ensure sessionCreated listener is called
                assertTrue (testListener.isCreated());
                assertEquals(1, m.getSessionsCreated());
                assertEquals(1, ((DefaultSessionCache)m.getSessionCache()).getSessionsMax());
                assertEquals(1, ((DefaultSessionCache)m.getSessionCache()).getSessionsTotal());
                
                //now delete the session
                Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=delete");
                request.header("Cookie", sessionCookie);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                //ensure sessionDestroyed listener is called
                assertTrue(testListener.isDestroyed());
                assertEquals(0, ((DefaultSessionCache)m.getSessionCache()).getSessionsCurrent());
                assertEquals(1, ((DefaultSessionCache)m.getSessionCache()).getSessionsMax());
                assertEquals(1, ((DefaultSessionCache)m.getSessionCache()).getSessionsTotal());

                // The session is not there anymore, even if we present an old cookie
                request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=check");
                request.header("Cookie", sessionCookie);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                assertEquals(0, ((DefaultSessionCache)m.getSessionCache()).getSessionsCurrent());
                assertEquals(1,  ((DefaultSessionCache)m.getSessionCache()).getSessionsMax());
                assertEquals(1, ((DefaultSessionCache)m.getSessionCache()).getSessionsTotal());
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
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("create".equals(action))
            {
                request.getSession(true);
            }
            else if ("delete".equals(action))
            {
                HttpSession s = request.getSession(false);
                assertNotNull(s);
                s.invalidate();
                s = request.getSession(false);
                assertNull(s);
            }
            else
            {
               HttpSession s = request.getSession(false);
               assertNull(s);
            }
        }
    }

    public static class TestEventListener implements HttpSessionListener
    {
        boolean wasCreated;
        boolean wasDestroyed;

        public void sessionCreated(HttpSessionEvent se)
        {
            wasCreated = true;
        }

        public void sessionDestroyed(HttpSessionEvent se)
        {
           wasDestroyed = true;
        }

        public boolean isDestroyed()
        {
            return wasDestroyed;
        }


        public boolean isCreated()
        {
            return wasCreated;
        }

    }

}
