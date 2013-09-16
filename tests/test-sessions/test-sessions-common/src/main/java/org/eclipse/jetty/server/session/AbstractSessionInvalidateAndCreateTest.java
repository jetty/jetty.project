//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * AbstractSessionInvalidateAndCreateTest
 * 
 * This test verifies that invalidating an existing session and creating 
 * a new session within the scope of a single request will expire the 
 * newly created session correctly (removed from the server and session listeners called).
 * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=377610
 */
public abstract class AbstractSessionInvalidateAndCreateTest
{
    public class MySessionListener implements HttpSessionListener
    {
        List<String> destroys;
        
        public void sessionCreated(HttpSessionEvent e)
        {
            
        }

        public void sessionDestroyed(HttpSessionEvent e)
        {
            if (destroys == null)
                destroys = new ArrayList<String>();
            
            destroys.add((String)e.getSession().getAttribute("identity"));
        }
    }
    
    public abstract AbstractTestServer createServer(int port, int max, int scavenge);
    
    

    public void pause(int scavengePeriod)
    {
        try
        {
            Thread.sleep(scavengePeriod * 2500L);
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }
    
    @Test
    public void testSessionScavenge() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 1;
        int scavengePeriod = 2;
        AbstractTestServer server = createServer(0, inactivePeriod, scavengePeriod);
        ServletContextHandler context = server.addContext(contextPath);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        context.addServlet(holder, servletMapping);
        MySessionListener listener = new MySessionListener();
        context.getSessionHandler().addEventListener(listener);
        server.start();
        int port1 = server.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.setConnectorType(HttpClient.CONNECTOR_SOCKET);
            client.start();
            try
            {
                String url = "http://localhost:" + port1 + contextPath + servletMapping;


                // Create the session
                ContentExchange exchange1 = new ContentExchange(true);
                exchange1.setMethod(HttpMethods.GET);
                exchange1.setURL(url + "?action=init");
                client.send(exchange1);
                exchange1.waitForDone();
                assertEquals(HttpServletResponse.SC_OK,exchange1.getResponseStatus());
                String sessionCookie = exchange1.getResponseFields().getStringField("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");


                // Make a request which will invalidate the existing session and create a new one
                ContentExchange exchange2 = new ContentExchange(true);
                exchange2.setMethod(HttpMethods.GET);
                exchange2.setURL(url + "?action=test");
                exchange2.getRequestFields().add("Cookie", sessionCookie);
                client.send(exchange2);
                exchange2.waitForDone();
                assertEquals(HttpServletResponse.SC_OK,exchange2.getResponseStatus());

                // Wait for the scavenger to run, waiting 2.5 times the scavenger period
                pause(scavengePeriod);

                //test that the session created in the last test is scavenged:
                //the HttpSessionListener should have been called when session1 was invalidated and session2 was scavenged
                assertTrue(listener.destroys.contains("session1"));
                assertTrue(listener.destroys.contains("session2"));
                //session2's HttpSessionBindingListener should have been called when it was scavenged
                assertTrue(servlet.unbound);
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
        private boolean unbound = false;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("identity", "session1");
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                if (session != null)
                {
                    session.invalidate();
                    
                    //now make a new session
                    session = request.getSession(true);
                    session.setAttribute("identity", "session2");
                    session.setAttribute("listener", new HttpSessionBindingListener()
                    {
                        
                        public void valueUnbound(HttpSessionBindingEvent event)
                        {
                            unbound = true;
                        }
                        
                        public void valueBound(HttpSessionBindingEvent event)
                        {
                            
                        }
                    });
                }
            }
        }
    }
}
