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

package org.eclipse.jetty.ee9.session;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AbstractClusteredOrphanedSessionTest
 *
 * Mimic node1 creating a session then crashing. Check that node2 will
 * eventually scavenge the orphaned session, even if the session was
 * never used on node2.
 */
public abstract class AbstractClusteredOrphanedSessionTest extends AbstractSessionTestBase
{
    /**
     * @throws Exception on test failure
     */
    @Test
    public void testOrphanedSession() throws Exception
    {
        // Disable scavenging for the first server, so that we simulate its "crash".
        String contextPath = "/";
        String servletMapping = "/server";
        int inactivePeriod = 5;
        DefaultSessionCacheFactory cacheFactory1 = new DefaultSessionCacheFactory();
        cacheFactory1.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cacheFactory1.setFlushOnResponseCommit(true); //ensure session is saved before response comes back
        SessionDataStoreFactory storeFactory1 = createSessionDataStoreFactory();
        if (storeFactory1 instanceof AbstractSessionDataStoreFactory)
        {
            ((AbstractSessionDataStoreFactory)storeFactory1).setGracePeriodSec(0);
        }

        SessionTestSupport server1 = new SessionTestSupport(0, inactivePeriod, -1, cacheFactory1, storeFactory1);
        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
        try
        {
            server1.start();
            int port1 = server1.getPort();
            int scavengePeriod = 2;

            DefaultSessionCacheFactory cacheFactory2 = new DefaultSessionCacheFactory();
            SessionDataStoreFactory storeFactory2 = createSessionDataStoreFactory();
            if (storeFactory2 instanceof AbstractSessionDataStoreFactory)
            {
                ((AbstractSessionDataStoreFactory)storeFactory2).setGracePeriodSec(0);
            }
            SessionTestSupport server2 = new SessionTestSupport(0, inactivePeriod, scavengePeriod, cacheFactory2, storeFactory2);
            server2.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
            try
            {
                server2.start();
                int port2 = server2.getPort();
                HttpClient client = new HttpClient();
                client.start();
                try
                {
                    // Connect to server1 to create a session and get its session cookie
                    ContentResponse response1 = client.GET("http://localhost:" + port1 + contextPath + servletMapping.substring(1) + "?action=init");
                    assertEquals(HttpServletResponse.SC_OK, response1.getStatus());
                    String sessionCookie = response1.getHeaders().get("Set-Cookie");
                    assertNotNull(sessionCookie);
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)([Pp])ath=", "$1\\$Path=");

                    // Wait for the session to expire.
                    // The first node does not do any scavenging, but the session
                    // must be removed by scavenging done in the other node.
                    Thread.sleep(TimeUnit.SECONDS.toMillis(inactivePeriod + 2L * scavengePeriod));

                    // Perform one request to server2 to be sure that the session has been expired
                    Request request = client.newRequest("http://localhost:" + port2 + contextPath + servletMapping.substring(1) + "?action=check");
                    HttpField cookie = new HttpField("Cookie", sessionCookie);
                    request.headers(headers -> headers.put(cookie));
                    ContentResponse response2 = request.send();
                    assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
                }
                finally
                {
                    client.stop();
                }
            }
            finally
            {
                server2.stop();
            }
        }
        finally
        {
            server1.stop();
        }
    }

    public static class TestServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("A", "A");
            }
            else if ("remove".equals(action))
            {
                HttpSession session = request.getSession(false);
                session.invalidate();
                //assertTrue(session == null);
            }
            else if ("check".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNull(session);
            }
        }
    }
}
