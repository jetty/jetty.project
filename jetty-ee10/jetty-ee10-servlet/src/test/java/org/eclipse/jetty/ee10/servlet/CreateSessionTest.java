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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.DefaultSessionIdManager;
import org.eclipse.jetty.session.HouseKeeper;
import org.eclipse.jetty.session.NullSessionDataStoreFactory;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CreateSessionTest
{
    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;
        public String _id = null;
       
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if (action != null && action.startsWith("create"))
            {
                HttpSession session = request.getSession(true);
                assertNotNull(session);
                _id = session.getId();
                session.setAttribute("value", 1);
                return;
            }
            else if (action != null && "test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                assertEquals(_id, session.getId());
                return;
            }
        }
    }

    @Test
    public void testSimpleSessionCreation() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);
        
        DefaultSessionIdManager sessionIdManager = new DefaultSessionIdManager(server);
        server.addBean(sessionIdManager, true);
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        server.addBean(cacheFactory);
        
        SessionDataStoreFactory storeFactory = new NullSessionDataStoreFactory();
        server.addBean(storeFactory);
        
        HouseKeeper housekeeper = new HouseKeeper();
        housekeeper.setIntervalSec(-1); //turn off scavenging
        sessionIdManager.setSessionHouseKeeper(housekeeper);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath(contextPath);
        server.setHandler(context);
        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setSessionIdManager(sessionIdManager);
        sessionHandler.setMaxInactiveInterval(-1); //immortal session
        context.setSessionHandler(sessionHandler);

        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        context.addServlet(holder, servletMapping);

        server.start();
        int port = connector.getLocalPort();
        try (StacklessLogging stackless = new StacklessLogging(CreateSessionTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();
            
            //make a session
            String url = "http://localhost:" + port + contextPath + servletMapping + "?action=create";

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            
            ContentResponse response2 = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=test");
            assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
        }
        finally
        {
            server.stop();
        }
    }
}
