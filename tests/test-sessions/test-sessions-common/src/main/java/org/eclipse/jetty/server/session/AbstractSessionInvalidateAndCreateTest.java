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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Test;

/**
 * AbstractSessionInvalidateAndCreateTest
 *
 * This test verifies that invalidating an existing session and creating
 * a new session within the scope of a single request will expire the
 * newly created session correctly (removed from the server and session listeners called).
 * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=377610
 */
public abstract class AbstractSessionInvalidateAndCreateTest extends AbstractTestBase
{
    public class MySessionListener implements HttpSessionListener
    {
        List<Integer> destroys = new ArrayList<>();

        public void sessionCreated(HttpSessionEvent e)
        {

        }

        public void sessionDestroyed(HttpSessionEvent e)
        {
            destroys.add(e.getSession().hashCode());
        }
    }



    public void pause(int scavengePeriod)
    {
        try
        {
            Thread.sleep(scavengePeriod * 1000L);
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
        int inactivePeriod = 6;
        int scavengePeriod = 3;
        AbstractTestServer server = createServer(0, inactivePeriod, scavengePeriod, SessionCache.NEVER_EVICT);
        ServletContextHandler context = server.addContext(contextPath);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        context.addServlet(holder, servletMapping);
        MySessionListener listener = new MySessionListener();
        context.getSessionHandler().addEventListener(listener);
    
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
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
                
                // Make a request which will invalidate the existing session and create a new one
                Request request2 = client.newRequest(url + "?action=test");
                request2.header("Cookie", sessionCookie);
                ContentResponse response2 = request2.send();
                assertEquals(HttpServletResponse.SC_OK,response2.getStatus());

                // Wait for the scavenger to run
                pause(inactivePeriod+(2*scavengePeriod));

                //test that the session created in the last test is scavenged:
                //the HttpSessionListener should have been called when session1 was invalidated and session2 was scavenged
                assertTrue(listener.destroys.size() == 2);
                assertTrue(listener.destroys.get(0) != listener.destroys.get(1)); //ensure 2 different objects
                //session2's HttpSessionBindingListener should have been called when it was scavenged
                assertTrue(servlet.listener.unbound);
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
    
    public static class Foo implements Serializable
    {
        public boolean bar = false;
        
        public boolean getBar() { return bar;};
    }

    public static class MySessionBindingListener implements HttpSessionBindingListener, Serializable
    {
        private boolean unbound = false;
        
        public void valueUnbound(HttpSessionBindingEvent event)
        {
            unbound = true;
        }

        public void valueBound(HttpSessionBindingEvent event)
        {

        }
    }
    
    public static class TestServlet extends HttpServlet
    {
        public MySessionBindingListener listener = new MySessionBindingListener();
       

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("identity", "session1");
                session.setMaxInactiveInterval(-1); //don't let this session expire, we want to explicitly invalidate it
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                if (session != null)
                {
                    String oldId = session.getId();

                    //invalidate existing session
                    session.invalidate();

                    //now try to access the invalid session
                    try
                    {
                        session.getAttribute("identity");
                        fail("Session should be invalid");
                    }
                    catch (IllegalStateException e)
                    {
                        assertNotNull(e.getMessage());
                        assertTrue(e.getMessage().contains("id"));
                    }

                    //now make a new session
                    session = request.getSession(true);
                    String newId = session.getId();
                    assertTrue(!newId.equals(oldId));
                    assertTrue (session.getAttribute("identity")==null);
                    session.setAttribute("identity", "session2");
                    session.setAttribute("listener", listener);
                }
                else
                    fail("Session already missing");
            }
        }
    }
}
