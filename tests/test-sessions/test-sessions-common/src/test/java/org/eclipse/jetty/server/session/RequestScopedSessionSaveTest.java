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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 *
 *
 */
public class RequestScopedSessionSaveTest
{
    public class RequestAwareSessionDataStore extends AbstractSessionDataStore
    { 
        public Map<String, SessionData> _map = new ConcurrentHashMap<>();
        
        @Override
        public boolean isPassivating()
        {
            return false;
        }

        @Override
        public boolean delete(String id) throws Exception
        {
            return (_map.remove(id) != null);
        }

        @Override
        public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
        {
            //check that the request attribute has been set in the scope
            String reqValue = RequestAwareContextScopeListener.__requestAttribute.get();
            assertNotNull(reqValue);
            _map.put(id,  data);
        }

        @Override
        public SessionData doLoad(String id) throws Exception
        {
            //check that the request attribute has been set in the scope
            String reqValue = RequestAwareContextScopeListener.__requestAttribute.get();
            assertNotNull(reqValue);
            
            SessionData sd = _map.get(id);
            if (sd == null)
                return null;
            SessionData nsd = new SessionData(id, "", "", System.currentTimeMillis(), System.currentTimeMillis(), System.currentTimeMillis(), 0);
            nsd.copy(sd);
            return nsd;
        }

        @Override
        public boolean doExists(String id) throws Exception
        {
            return _map.containsKey(id);
        }

        @Override
        public Set<String> doCheckExpired(Set<String> candidates, long time)
        {
            HashSet<String> set = new HashSet<>();
            long now = System.currentTimeMillis();

            for (SessionData d : _map.values())
            {
                if (d.getExpiry() > 0 && d.getExpiry() <= now)
                    set.add(d.getId());
            }
            return set;
        }

        @Override
        public Set<String> doGetExpired(long before)
        {
            Set<String> set =  new HashSet<>();

            for (SessionData d:_map.values())
            {
                if (d.getExpiry() > 0 && d.getExpiry() <= before)
                    set.add(d.getId());
            }
            return set;
        }

        @Override
        public void doCleanOrphans(long time)
        {
            //noop
        }
    }
    
    /**
     * Place an attribute from a request into a threadlocal on scope entry, and then remove it
     * on scope exit.
     *
     */
    public static class RequestAwareContextScopeListener implements ContextHandler.ContextScopeListener
    {
        public static final ThreadLocal<String> __requestAttribute = new ThreadLocal<>();
        public static final String ATTRIBUTE = "cheese";
        
        @Override
        public void enterScope(Context context, Request request, Object reason)
        {
            //set a request attribute
            if (request != null)
            {
                request.setAttribute(ATTRIBUTE, (System.currentTimeMillis() % 2 == 0 ? "Parmigiano" : "Reblochon"));
                __requestAttribute.set((String)request.getAttribute(ATTRIBUTE));
            }
        }

        @Override
        public void exitScope(Context context, Request request)
        {
            __requestAttribute.remove();
        }
    }

    public class TestServlet extends HttpServlet
    {
        String _attributeName;

        public TestServlet(String attributeName)
        {
            _attributeName = attributeName;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException
        {
            //ensure session
            HttpSession session = req.getSession(true);
            assertNotNull(session);

            //also set same as session attribute
            session.setAttribute(_attributeName, req.getAttribute(_attributeName));
            resp.getWriter().println(_attributeName + ":" + req.getAttribute(_attributeName));
        }
    }

    @Test
    public void testSessionSave() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 20;
        int scavengePeriod = 3;
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cacheFactory.setSaveOnCreate(true);
        AbstractSessionDataStoreFactory storeFactory = new AbstractSessionDataStoreFactory()
        {
            public SessionDataStore getSessionDataStore(SessionHandler handler) throws Exception
            {
                return new RequestAwareSessionDataStore();
            }
        };

        storeFactory.setSavePeriodSec(10);
        TestServer server = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        TestServlet servlet = new TestServlet(RequestAwareContextScopeListener.ATTRIBUTE);
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        contextHandler.addEventListener(new RequestAwareContextScopeListener());
        server.start();
        int port1 = server.getPort();

        try (StacklessLogging stackless = new StacklessLogging(RequestScopedSessionSaveTest.class))
        {
            HttpClient client = new HttpClient();
            try
            {
                client.start();
                String url = "http://localhost:" + port1 + contextPath + servletMapping;

                //make a request to set up a session on the server
                ContentResponse response = client.GET(url);
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
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
