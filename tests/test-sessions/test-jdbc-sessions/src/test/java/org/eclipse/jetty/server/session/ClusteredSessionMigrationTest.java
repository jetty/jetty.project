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
import java.io.PrintWriter;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClusteredSessionMigrationTest
 *
 * Test that a session that is active on node 1 can be loaded by node 2.
 *
 * This test is applicable to any of the SessionDataStores that support
 * clustering, but does not test the actual SessionDataStore itself.
 * Rather, it tests all of the machinery above the SessionDataStore. Thus,
 * to reduce test time, we only apply it to the JDBCSessionDataStore.
 */
@Testcontainers(disabledWithoutDocker = true)
public class ClusteredSessionMigrationTest extends AbstractTestBase
{

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return JdbcTestHelper.newSessionDataStoreFactory();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        JdbcTestHelper.shutdown(null);
    }

    @Test
    public void testSessionMigration() throws Exception
    {
        String contextPath = "/";
        String servletMapping = "/server";

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cacheFactory.setSaveOnCreate(true); //immediately save the session when it is created so node2 can see it
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(TestServer.DEFAULT_SCAVENGE_SEC);

        TestServer server1 = new TestServer(0, TestServer.DEFAULT_MAX_INACTIVE, TestServer.DEFAULT_SCAVENGE_SEC,
            cacheFactory, storeFactory);
        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);

        try
        {
            server1.start();
            int port1 = server1.getPort();

            //Configure a cache and store same way for server2
            DefaultSessionCacheFactory cacheFactory2 = new DefaultSessionCacheFactory();
            cacheFactory2.setEvictionPolicy(SessionCache.NEVER_EVICT);
            cacheFactory2.setSaveOnCreate(true);
            SessionDataStoreFactory storeFactory2 = createSessionDataStoreFactory();

            TestServer server2 = new TestServer(0, TestServer.DEFAULT_MAX_INACTIVE, TestServer.DEFAULT_SCAVENGE_SEC,
                cacheFactory2, storeFactory2);
            server2.addContext(contextPath).addServlet(TestServlet.class, servletMapping);

            try
            {
                server2.start();
                int port2 = server2.getPort();

                HttpClient client = new HttpClient();
                client.start();
                try
                {
                    // Perform one request to server1 to create a session
                    int value = 1;
                    Request request1 = client.POST("http://localhost:" + port1 + contextPath + servletMapping.substring(1) + "?action=set&value=" + value);
                    ContentResponse response1 = request1.send();
                    assertEquals(HttpServletResponse.SC_OK, response1.getStatus());
                    String sessionCookie = response1.getHeaders().get("Set-Cookie");
                    assertTrue(sessionCookie != null);
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                    // Perform a request to server2 using the session cookie from the previous request
                    // This should migrate the session from server1 to server2.
                    Request request2 = client.newRequest("http://localhost:" + port2 + contextPath + servletMapping.substring(1) + "?action=get");
                    request2.header("Cookie", sessionCookie);
                    ContentResponse response2 = request2.send();
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
        private static final long serialVersionUID = 1L;
        
        private static long createTime = 0;

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
            if ("set".equals(action))
            {
                if (session == null)
                    session = request.getSession(true);
                createTime = session.getCreationTime();
                int value = Integer.parseInt(request.getParameter("value"));
                session.setAttribute("value", value);
                PrintWriter writer = response.getWriter();
                writer.println(value);
                writer.flush();
            }
            else if ("get".equals(action))
            {
                //We cannot test if the session contains the attribute node1
                //set, because it may not have finished writing the attribute
                //by the time its response returned to the client and the request
                //was sent to node2. Just test the create time, which should be
                //saved.
                assertEquals(createTime, session.getCreationTime());
            }
        }
    }
}
