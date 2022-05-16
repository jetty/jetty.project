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

package org.eclipse.jetty.ee10.session;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.session.AbstractSessionDataStore;
import org.eclipse.jetty.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionDataStore;
import org.eclipse.jetty.session.SessionManager;
import org.eclipse.jetty.session.UnreadableSessionDataException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DeleteUnloadableSessionTest
{
    public static class DelSessionDataStore extends AbstractSessionDataStore
    {
        Object o = new Object();

        String unloadableId = null;

        @Override
        public boolean isPassivating()
        {
            return true;
        }

        @Override
        public boolean doExists(String id)
        {
            return o != null;
        }

        @Override
        public SessionData doLoad(String id) throws Exception
        {
            unloadableId = id;
            throw new UnreadableSessionDataException(id, null, new Exception("fake"));
        }

        @Override
        public boolean delete(String id)
        {
            if (id.equals(unloadableId))
            {
                o = null;
                return true;
            }
            return false;
        }

        @Override
        public void doStore(String id, SessionData data, long lastSaveTime)
        {
            //pretend it was saved
        }

        @Override
        public Set<String> doCheckExpired(Set<String> candidates, long timeLimit)
        {
            return Collections.emptySet();
        }

        @Override
        public Set<String> doGetExpired(long timeLimit)
        {
            return Collections.emptySet();
        }

        @Override
        public void doCleanOrphans(long timeLimit)
        {
           //noop
        }
        
    }

    public static class DelSessionDataStoreFactory extends AbstractSessionDataStoreFactory
    {
        @Override
        public SessionDataStore getSessionDataStore(SessionManager manager)
        {
            return new DelSessionDataStore();
        }
    }

    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");

            if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNull(session);
            }
        }
    }

    /**
     * Test that session data that can't be loaded results in a null Session object
     */
    @Test
    public void testDeleteUnloadableSession() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = -1;
        int scavengePeriod = 100;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cacheFactory.setRemoveUnloadableSessions(true);
        AbstractSessionDataStoreFactory storeFactory = new DelSessionDataStoreFactory();
        storeFactory.setGracePeriodSec(scavengePeriod);

        SessionTestSupport server = new SessionTestSupport(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        ServletContextHandler context = server.addContext(contextPath);

        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        context.addServlet(holder, servletMapping);

        try (StacklessLogging ignored = new StacklessLogging(DeleteUnloadableSessionTest.class.getPackage(), org.eclipse.jetty.session.Session.class.getPackage()))
        {
            server.start();
            int port = server.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                String sessionCookie = "JSESSIONID=w0rm3zxpa6h1zg1mevtv76b3te00.w0;$Path=/";
                Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=test");
                HttpField cookie = new HttpField("Cookie", sessionCookie);
                request.headers(headers -> headers.put(cookie));
                ContentResponse response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                assertFalse(context.getSessionHandler().getSessionCache().getSessionDataStore().exists(SessionTestSupport.extractSessionId(sessionCookie)));
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
}
