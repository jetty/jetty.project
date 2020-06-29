//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.session;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test having multiple session cookies in a request.
 */
public class DuplicateCookieTest
{
    @Test
    public void testMultipleSessionCookiesOnlyOneExists() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        HttpClient client = null;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        TestServer server1 = new TestServer(0, -1, -1, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging ignored = new StacklessLogging(DuplicateCookieTest.class.getPackage()))
        {
            //create a valid session
            createUnExpiredSession(contextHandler.getSessionHandler().getSessionCache(),
                contextHandler.getSessionHandler().getSessionCache().getSessionDataStore(),
                "4422");

            client = new HttpClient();
            client.start();

            //make a request with another session cookie in there that does not exist
            Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=check");
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=123")); //doesn't exist
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=4422")); //does exist
            ContentResponse response = request.send();
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            assertEquals("4422", response.getContentAsString());
        }
        finally
        {
            server1.stop();
            client.stop();
        }
    }

    @Test
    public void testMultipleSessionCookiesOnlyOneValid() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        HttpClient client = null;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        TestServer server1 = new TestServer(0, -1, -1, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging ignored = new StacklessLogging(DuplicateCookieTest.class.getPackage()))
        {
            //create a valid session
            createUnExpiredSession(contextHandler.getSessionHandler().getSessionCache(),
                contextHandler.getSessionHandler().getSessionCache().getSessionDataStore(),
                "1122");
            //create an invalid session
            createInvalidSession(contextHandler.getSessionHandler().getSessionCache(),
                contextHandler.getSessionHandler().getSessionCache().getSessionDataStore(),
                "2233");

            client = new HttpClient();
            client.start();

            //make a request with another session cookie in there that is not valid
            Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=check");
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=1122")); //is valid
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=2233")); //is invalid
            ContentResponse response = request.send();
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            assertEquals("1122", response.getContentAsString());
        }
        finally
        {
            server1.stop();
            client.stop();
        }
    }

    @Test
    public void testMultipleSessionCookiesMultipleExists() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        HttpClient client = null;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();

        TestServer server1 = new TestServer(0, -1, -1, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try (StacklessLogging ignored = new StacklessLogging(DuplicateCookieTest.class.getPackage()))
        {
            //create some of unexpired sessions
            createUnExpiredSession(contextHandler.getSessionHandler().getSessionCache(),
                contextHandler.getSessionHandler().getSessionCache().getSessionDataStore(),
                "1234");
            createUnExpiredSession(contextHandler.getSessionHandler().getSessionCache(),
                contextHandler.getSessionHandler().getSessionCache().getSessionDataStore(),
                "5678");
            createUnExpiredSession(contextHandler.getSessionHandler().getSessionCache(),
                contextHandler.getSessionHandler().getSessionCache().getSessionDataStore(),
                "9111");

            client = new HttpClient();
            client.start();

            //make a request with multiple valid session ids
            Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=check");
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=1234"));
            request.headers(headers -> headers.add("Cookie", "JSESSIONID=5678"));
            ContentResponse response = request.send();
            assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getStatus());
        }
        finally
        {
            server1.stop();
            client.stop();
        }
    }

    public Session createUnExpiredSession(SessionCache cache, SessionDataStore store, String id) throws Exception
    {
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData(id, now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        Session s = cache.newSession(data);
        cache.add(id, s);
        return s;
    }

    public Session createInvalidSession(SessionCache cache, SessionDataStore store, String id) throws Exception
    {
        Session session = createUnExpiredSession(cache, store, id);
        session._state = Session.State.INVALID;
        return session;
    }

    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if (StringUtil.isBlank(action))
                return;

            if (action.equalsIgnoreCase("check"))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                httpServletResponse.getWriter().print(session.getId());
            }
            else if (action.equalsIgnoreCase("create"))
            {
                request.getSession(true);
            }
        }
    }
}
