//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.After;
import org.junit.Test;


/**
 * ModifyMaxInactiveIntervalTest
 *
 *
 *
 */
public class ModifyMaxInactiveIntervalTest
{

    public static int __inactive = 4;
    public static int newMaxInactive = 20;
    public static int __scavenge = 1;

        
    @Test
    public void testSessionExpiryAfterModifiedMaxInactiveInterval() throws Exception
    {
        AbstractTestServer server = new JdbcTestServer(0,__inactive,__scavenge, SessionCache.NEVER_EVICT);
        
        ServletContextHandler ctxA = server.addContext("/mod");
        ctxA.addServlet(TestModServlet.class, "/test");
      
        server.start();
        int port=server.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // Perform a request to create a session
                
                ContentResponse response = client.GET("http://localhost:" + port + "/mod/test?action=create");
                
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                //do another request to change the maxinactive interval
                Request request = client.newRequest("http://localhost:" + port + "/mod/test?action=change&val="+newMaxInactive);
                request.header("Cookie", sessionCookie);
                response = request.send();

                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                               
                //wait for longer than the old inactive interval
                Thread.currentThread().sleep(10*1000L);
                
                //do another request using the cookie to ensure the session is still there
                request= client.newRequest("http://localhost:" + port + "/mod/test?action=test");
                request.header("Cookie", sessionCookie);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
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
    
    
    @After
    public void tearDown() throws Exception 
    {
        JdbcTestServer.shutdown(null);
    }
    
    public static class TestModServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            
            if ("create".equals(action))
            {
                HttpSession session = request.getSession(true);
                return;
            }
            
            if ("change".equals(action))
            {
                HttpSession session = request.getSession(false);
                if (session == null)
                    throw new ServletException("Session is null for action=change");

                String tmp = request.getParameter("val");
                int interval = -1;
                interval = (tmp==null?-1:Integer.parseInt(tmp));
     
                if (interval > 0)
                    session.setMaxInactiveInterval(interval);
                return;
            }
            
            if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                if (session == null)
                    throw new ServletException("Session does not exist");
                assertEquals(ModifyMaxInactiveIntervalTest.newMaxInactive, session.getMaxInactiveInterval());
                return;
            }
        }
    }
    
}
