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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.server.session.Session.SessionInactivityTimeout;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.StringUtil;
import org.junit.Test;

/**
 * AbstractSessionExpiryTest
 *
 *
 */
public abstract class AbstractSessionExpiryTest extends AbstractTestBase
{

    public void pause(int period)
    {
        try
        {
            Thread.sleep(period * 1000L);
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }
    
    public class TestHttpSessionListener implements HttpSessionListener
    {
        public List<String> createdSessions = new ArrayList<String>();
        public List<String> destroyedSessions = new ArrayList<String>();
        
        public void sessionDestroyed(HttpSessionEvent se)
        {
            destroyedSessions.add(se.getSession().getId());
        }
        
        public void sessionCreated(HttpSessionEvent se)
        {
            createdSessions.add(se.getSession().getId());
        }
    };
    

    /**
     * Check session is preserved over stop/start
     * @throws Exception
     */
    @Test
    public void testSessionNotExpired() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 20;
        int scavengePeriod = 10;
        AbstractTestServer server1 = createServer(0, inactivePeriod, scavengePeriod, SessionCache.NEVER_EVICT);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        server1.addContext(contextPath).addServlet(holder, servletMapping);

        HttpClient client = new HttpClient();
        try
        {
            server1.start();
            int port1 = server1.getPort();

            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK,response.getStatus());
            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            // Mangle the cookie, replacing Path with $Path, etc.
            sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

            //now stop the server
            server1.stop();
   

            //start the server again, before the session times out
            server1.start();
            port1 = server1.getPort();
            url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make another request, the session should not have expired
            Request request = client.newRequest(url + "?action=notexpired");
            request.getHeaders().add("Cookie", sessionCookie);
            ContentResponse response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());

        }
        finally
        {
            client.stop();
            server1.stop();
        }
    }
    

    /**
     * Check that a session that expires whilst the server is stopped will not be
     * able to be used when the server restarts
     * @throws Exception
     */
    @Test
    public void testSessionExpiry() throws Exception
    {
     
        
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 4;
        int scavengePeriod = 1;
        AbstractTestServer server1 = createServer(0, inactivePeriod, scavengePeriod, SessionCache.NEVER_EVICT);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler context = server1.addContext(contextPath);
        context.addServlet(holder, servletMapping);
        TestHttpSessionListener listener = new TestHttpSessionListener();
        
        context.getSessionHandler().addEventListener(listener);
        
        server1.start();
        int port1 = server1.getPort();

        try
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            ContentResponse response1 = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK,response1.getStatus());
            String sessionCookie = response1.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            // Mangle the cookie, replacing Path with $Path, etc.
            sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
            
            String sessionId = AbstractTestServer.extractSessionId(sessionCookie);     

            verifySessionCreated(listener,sessionId);
            
            //now stop the server
            server1.stop();
            
            //and wait until the session should have expired
            pause(inactivePeriod);

            //restart the server
            server1.start();
            
            //and wait until the scavenger has run
            pause(inactivePeriod+(scavengePeriod*2));
            
            port1 = server1.getPort();
            url = "http://localhost:" + port1 + contextPath + servletMapping;
            
            //make another request, the session should have expired
            Request request = client.newRequest(url + "?action=test");
            request.getHeaders().add("Cookie", sessionCookie);
            ContentResponse response2 = request.send();

            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
            String cookie2 = response2.getHeaders().get("Set-Cookie");
            assertTrue (!cookie2.equals(sessionCookie));
            verifySessionDestroyed (listener, sessionId);
        }
        finally
        {
            server1.stop();
        }     
    }
    
    
    @Test
    public void testRequestForSessionWithChangedTimeout () throws Exception
    {
      String contextPath = "";
      String servletMapping = "/server";
      int inactivePeriod = 5;
      int scavengePeriod = 1;
      AbstractTestServer server1 = createServer(0, inactivePeriod, scavengePeriod, SessionCache.NEVER_EVICT);
      ChangeTimeoutServlet servlet = new ChangeTimeoutServlet();
      ServletHolder holder = new ServletHolder(servlet);
      ServletContextHandler context = server1.addContext(contextPath);
      context.addServlet(holder, servletMapping);
      TestHttpSessionListener listener = new TestHttpSessionListener();
      
      context.getSessionHandler().addEventListener(listener);
      
      server1.start();
      int port1 = server1.getPort();

      try
      {
          HttpClient client = new HttpClient();
          client.start();
          String url = "http://localhost:" + port1 + contextPath + servletMapping;

          //make a request to set up a session on the server with the session manager's inactive timeout
          ContentResponse response = client.GET(url + "?action=init");
          assertEquals(HttpServletResponse.SC_OK,response.getStatus());
          String sessionCookie = response.getHeaders().get("Set-Cookie");
          assertTrue(sessionCookie != null);
          // Mangle the cookie, replacing Path with $Path, etc.
          sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
             
         
          //make another request to change the session timeout to a larger value
          int newInactivePeriod = 100;
          Request request = client.newRequest(url + "?action=change&val="+newInactivePeriod);
          request.getHeaders().add("Cookie", sessionCookie);
          response = request.send();
          assertEquals(HttpServletResponse.SC_OK,response.getStatus());
          
          //stop and restart the session manager to ensure it needs to reload the session
          context.stop();
          context.start();

          //wait until the session manager timeout has passed and re-request the session
          //which should still be valid
          pause(inactivePeriod);

          request = client.newRequest(url + "?action=check");
          request.getHeaders().add("Cookie", sessionCookie);
          response = request.send();
          assertEquals(HttpServletResponse.SC_OK,response.getStatus());
          String sessionCookie2 = response.getHeaders().get("Set-Cookie");
          assertNull(sessionCookie2);
          
      }
      finally
      {
          server1.stop();
      }     
    }
    
    
    
    public void verifySessionCreated (TestHttpSessionListener listener, String sessionId)
    {
        assertTrue(listener.createdSessions.contains(sessionId));
    }
    public void verifySessionDestroyed (TestHttpSessionListener listener, String sessionId)
    {
        assertTrue (listener.destroyedSessions.contains(sessionId));
    }



    public static class TestServlet extends HttpServlet
    {
        public String originalId = null;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("test", "test");
                originalId = session.getId();
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(true);
                assertTrue(session != null);
                assertTrue(!originalId.equals(session.getId()));
            }
            else if ("notexpired".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session != null);
                assertTrue(originalId.equals(session.getId()));
            }

        }
    }
    
    
    public static class ChangeTimeoutServlet extends HttpServlet
    {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("test", "test");
            }
            else if ("change".equals(action))
            {
                String tmp = request.getParameter("val");
                int val = (StringUtil.isBlank(tmp)?0:Integer.valueOf(tmp.trim()));
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                session.setMaxInactiveInterval(val);
            }
            else if ("check".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
            }
        }
    }
    
}
