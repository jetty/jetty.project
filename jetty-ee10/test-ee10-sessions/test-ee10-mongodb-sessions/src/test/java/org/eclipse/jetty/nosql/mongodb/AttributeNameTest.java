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

package org.eclipse.jetty.nosql.mongodb;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.ee10.session.SessionTestSupport;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.session.SessionCache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * AttributeNameTest
 *
 * Test that attribute names that have special characters with meaning to mongo (eg ".") are
 * properly escaped and not accidentally removed.
 * See bug: https://bugs.eclipse.org/bugs/show_bug.cgi?id=444595
 */
@Testcontainers(disabledWithoutDocker = true)
public class AttributeNameTest
{
    @BeforeAll
    public static void beforeClass() throws Exception
    {
        MongoTestHelper.createCollection();
    }

    @AfterAll
    public static void afterClass() throws Exception
    {
        MongoTestHelper.dropCollection();
        MongoTestHelper.shutdown();
    }

    @Test
    public void testAttributeNamesWithDots() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int maxInactivePeriod = 10000;
        int scavengePeriod = 20000;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);

        MongoSessionDataStoreFactory storeFactory = MongoTestHelper.newSessionDataStoreFactory();
        storeFactory.setGracePeriodSec(scavengePeriod);

        SessionTestSupport server1 = new SessionTestSupport(0, maxInactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        SessionTestSupport server2 = new SessionTestSupport(0, maxInactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        server2.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
        server2.start();
        int port2 = server2.getPort();

        try
        {

            HttpClient client = new HttpClient();
            client.start();
            try
            {

                // Perform one request to server1 to create a session with attribute with dotted name
                ContentResponse response = client.GET("http://localhost:" + port1 + contextPath + servletMapping + "?action=init");

                assertEquals(HttpServletResponse.SC_OK, response.getStatus());

                String resp = response.getContentAsString();

                String[] sessionTestResponse = resp.split("/");
                assertEquals("a.b.c", sessionTestResponse[0]);

                String sessionCookie = response.getHeaders().get(HttpHeader.SET_COOKIE);

                assertNotNull(sessionCookie);
                //Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)([Pp])ath=", "$1\\$Path=");

                //Make a request to the 2nd server which will do a refresh, use TestServlet to ensure that the
                //session attribute with dotted name is not removed
                Request request2 = client.newRequest("http://localhost:" + port2 + contextPath + servletMapping + "?action=get");
                HttpField cookie = new HttpField("Cookie", sessionCookie);
                request2.headers(headers -> headers.put(cookie));
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
            server1.stop();
            server2.stop();
        }
    }

    public static class TestServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("a.b.c", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
                sendResult(session, httpServletResponse.getWriter());
            }
            else
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                assertNotNull(session.getAttribute("a.b.c"));
                sendResult(session, httpServletResponse.getWriter());
            }
        }

        private void sendResult(HttpSession session, PrintWriter writer)
        {
            if (session != null)
            {
                if (session.getAttribute("a.b.c") != null)
                    writer.print("a.b.c/" + session.getAttribute("a.b.c"));
                else
                    writer.print("-/0");
            }
            else
            {
                writer.print("0/0");
            }
        }
    }
}
