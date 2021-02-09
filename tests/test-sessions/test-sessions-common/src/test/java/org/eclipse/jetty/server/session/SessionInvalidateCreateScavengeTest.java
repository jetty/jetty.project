//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SessionInvalidateCreateScavengeTest
 *
 * This test verifies that invalidating an existing session and creating
 * a new session within the scope of a single request will expire the
 * newly created session correctly (removed from the server and session listeners called).
 * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=377610
 */
public class SessionInvalidateCreateScavengeTest extends AbstractTestBase
{
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return new TestSessionDataStoreFactory();
    }

    @Test
    public void testSessionScavenge() throws Exception
    {
        String contextPath = "/";
        String servletMapping = "/server";
        int inactivePeriod = 6;
        int scavengePeriod = 1;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(scavengePeriod);

        TestServer server = new TestServer(0, inactivePeriod, scavengePeriod,
            cacheFactory, storeFactory);
        ServletContextHandler context = server.addContext(contextPath);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        context.addServlet(holder, servletMapping);
        MySessionListener listener = new MySessionListener();
        context.getSessionHandler().addEventListener(listener);

        try
        {
            server.start();
            int port1 = server.getPort();

            HttpClient client = new HttpClient();
            client.start();
            try
            {
                String url = "http://localhost:" + port1 + contextPath + servletMapping.substring(1);

                // Create the session
                ContentResponse response1 = client.GET(url + "?action=init");
                assertEquals(HttpServletResponse.SC_OK, response1.getStatus());
                String sessionCookie = response1.getHeaders().get("Set-Cookie");
                assertNotNull(sessionCookie);

                // Make a request which will invalidate the existing session and create a new one
                Request request2 = client.newRequest(url + "?action=test");
                ContentResponse response2 = request2.send();
                assertEquals(HttpServletResponse.SC_OK, response2.getStatus());

                // Wait for the scavenger to run
                Thread.sleep(TimeUnit.SECONDS.toMillis(inactivePeriod + 2 * scavengePeriod));

                //test that the session created in the last test is scavenged:
                //the HttpSessionListener should have been called when session1 was invalidated and session2 was scavenged
                assertEquals(2, listener.destroys.size());
                assertNotSame(listener.destroys.get(0), listener.destroys.get(1)); //ensure 2 different objects
                //session2's HttpSessionBindingListener should have been called when it was scavenged
                assertTrue(servlet.listener.unbound);
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

    public class MySessionListener implements HttpSessionListener
    {
        List<Integer> destroys = new ArrayList<>();

        public void sessionCreated(HttpSessionEvent e)
        {

        }

        public void sessionDestroyed(HttpSessionEvent e)
        {
            destroys.add(e.getSession().hashCode());
        }
    }

    public static class MySessionBindingListener implements HttpSessionBindingListener, Serializable
    {
        private static final long serialVersionUID = 1L;
        private boolean unbound = false;

        @Override
        public void valueUnbound(HttpSessionBindingEvent event)
        {
            unbound = true;
        }

        @Override
        public void valueBound(HttpSessionBindingEvent event)
        {

        }
    }

    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;
        public MySessionBindingListener listener = new MySessionBindingListener();

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("identity", "session1");
                session.setMaxInactiveInterval(-1); //don't let this session expire, we want to explicitly invalidate it
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                if (session != null)
                {
                    String oldId = session.getId();

                    //invalidate existing session
                    session.invalidate();

                    //now try to access the invalid session
                    HttpSession finalSession = session;
                    IllegalStateException x = assertThrows(IllegalStateException.class,
                        () -> finalSession.getAttribute("identity"),
                        "Session should be invalid");
                    assertThat(x.getMessage(), containsString("id"));

                    //now make a new session
                    session = request.getSession(true);
                    String newId = session.getId();
                    assertNotEquals(newId, oldId);
                    assertNull(session.getAttribute("identity"));
                    session.setAttribute("identity", "session2");
                    session.setAttribute("bindingListener", listener);
                }
                else
                    fail("Session already missing");
            }
        }
    }
}
