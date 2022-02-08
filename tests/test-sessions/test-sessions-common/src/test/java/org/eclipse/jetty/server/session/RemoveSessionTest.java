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

package org.eclipse.jetty.server.session;

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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RemoveSessionTest
 *
 * Test that invalidating a session does not return the session on the next request.
 */
public class RemoveSessionTest
{

    @Test
    public void testRemoveSession() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setFlushOnResponseCommit(true);
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        TestServer server = new TestServer(0, -1, -1, cacheFactory, storeFactory);

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
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);

                //ensure sessionCreated bindingListener is called
                assertTrue(testListener.isCreated());
                assertEquals(1, m.getSessionsCreated());
                assertEquals(1, ((DefaultSessionCache)m.getSessionCache()).getSessionsMax());
                assertEquals(1, ((DefaultSessionCache)m.getSessionCache()).getSessionsTotal());

                //now delete the session
                Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=delete");
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                //ensure sessionDestroyed bindingListener is called
                assertTrue(testListener.isDestroyed());
                assertEquals(0, ((DefaultSessionCache)m.getSessionCache()).getSessionsCurrent());
                assertEquals(1, ((DefaultSessionCache)m.getSessionCache()).getSessionsMax());
                assertEquals(1, ((DefaultSessionCache)m.getSessionCache()).getSessionsTotal());
                
                //check the session is no longer in the cache
                assertFalse(((AbstractSessionCache)m.getSessionCache()).contains(TestServer.extractSessionId(sessionCookie)));

                //check the session is not persisted any more
                assertFalse(m.getSessionCache().getSessionDataStore().exists(TestServer.extractSessionId(sessionCookie)));

                // The session is not there anymore, even if we present an old cookie
                request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=check");
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                assertEquals(0, ((DefaultSessionCache)m.getSessionCache()).getSessionsCurrent());
                assertEquals(1, ((DefaultSessionCache)m.getSessionCache()).getSessionsMax());
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
        private static final long serialVersionUID = 1L;

        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("create".equals(action))
            {
                HttpSession s = request.getSession(true);
                s.setAttribute("foo", "bar");
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

        @Override
        public void sessionCreated(HttpSessionEvent se)
        {
            wasCreated = true;
        }

        @Override
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
