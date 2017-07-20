//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.Serializable;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Test;

/**
 * SessionListenerTest
 *
 * Test that the HttpSessionBindingListeners are called.
 */
public class SessionListenerTest
{
    public static class MySessionBindingListener implements HttpSessionBindingListener, Serializable
    {
        boolean unbound = false;
        boolean bound = false;
        
        public void valueUnbound(HttpSessionBindingEvent event)
        {
            unbound = true;
        }

        public void valueBound(HttpSessionBindingEvent event)
        {
            bound = true;
        }
    }
    
    public static class Foo implements Serializable
    {
        public boolean bar = false;
        
        public boolean getBar() { return bar;};
    }

  
    
    @Test
    public void testListener() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 6;
        int scavengePeriod =  -1;

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory).setGracePeriodSec(scavengePeriod);

        TestServer server = new TestServer(0, inactivePeriod, scavengePeriod, 
                                           cacheFactory, storeFactory);
        ServletContextHandler context = server.addContext(contextPath);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        context.addServlet(holder, servletMapping);
    
        try
        {
            server.start();
            int port1 = server.getPort();
            
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                String url = "http://localhost:" + port1 + contextPath + servletMapping;


                // Create the session
                ContentResponse response1 = client.GET(url + "?action=init");
                assertEquals(HttpServletResponse.SC_OK,response1.getStatus());
                String sessionCookie = response1.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                assertTrue (servlet.listener.bound);
                
                
                // Make a request which will invalidate the existing session
                Request request2 = client.newRequest(url + "?action=test");
                ContentResponse response2 = request2.send();
                assertEquals(HttpServletResponse.SC_OK,response2.getStatus());

                assertTrue (servlet.listener.unbound);
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
        public static final MySessionBindingListener listener = new MySessionBindingListener();
       

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
         
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("foo", listener);
                assertNotNull(session);

            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                
                //invalidate existing session
                session.invalidate();
            }
        }
    }
}
