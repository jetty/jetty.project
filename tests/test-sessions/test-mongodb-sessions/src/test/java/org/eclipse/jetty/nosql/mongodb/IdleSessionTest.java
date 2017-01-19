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

package org.eclipse.jetty.nosql.mongodb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import org.eclipse.jetty.nosql.NoSqlSession;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Test;

/**
 * IdleSessionTest
 * 
 * Test that mongo sessions can be passivated if idle longer than a configurable
 * interval (which should be shorter than the expiry interval!)
 *
 */
public class IdleSessionTest
{
    public static TestServlet _servlet = new TestServlet();
    

    public MongoTestServer createServer(int port, int max, int scavenge)
    {
        MongoTestServer server =  new MongoTestServer(port,max,scavenge);

        return server;
    }
    

    public void pause (int sec)
    {
        try
        {
            Thread.sleep(sec * 1000L);
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    

    @Test
    public void testIdleSession() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 20; //sessions expire after 20 seconds
        int scavengePeriod = 1; //look for expired sessions every second
        int idlePeriod = 3; //after 3 seconds of inactivity, idle to disk
        
        
        MongoTestServer server1 = createServer(0, inactivePeriod, scavengePeriod);
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
        ((MongoSessionManager)contextHandler.getSessionHandler().getSessionManager()).setIdlePeriod(idlePeriod);
        contextHandler.addServlet(holder, servletMapping);
        server1.start();
        int port1 = server1.getPort();

        try
        {
            HttpClient client = new HttpClient();
            client.start();
            String url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK,response.getStatus());
            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            // Mangle the cookie, replacing Path with $Path, etc.
            sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

            //and wait until the session should be idled out
            pause(idlePeriod * 2);
            
            //check that the session is idle
            checkSessionIdle();

            //make another request to de-idle the session
            Request request = client.newRequest(url + "?action=test");
            request.getHeaders().add("Cookie", sessionCookie);
            ContentResponse response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());

            //check session de-idled
            checkSessionDeIdle();
            checkValue(2);
            
            //wait again for the session to be idled
            pause(idlePeriod * 2);

            //check that it is
            checkSessionIdle();
            
            //While idle, take some action to ensure that a deidle won't work, like
            //deleting all sessions in mongo
            assertTrue(server1.getServer().getSessionIdManager() instanceof MongoTestServer.TestMongoSessionIdManager);
            ((MongoTestServer.TestMongoSessionIdManager)server1.getServer().getSessionIdManager()).deleteAll();
            
            //now make a request for which deidle should fail
            request = client.newRequest(url + "?action=testfail");
            request.getHeaders().add("Cookie", sessionCookie);
            response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
            
            //Test trying to de-idle an expired session (ie before the scavenger can get to it)
            
            //make a request to set up a session on the server
            response = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK,response.getStatus());
            sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            // Mangle the cookie, replacing Path with $Path, etc.
            sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

            //and wait until the session should be idled out
            pause(idlePeriod * 2);
            
            //stop the scavenger
            ((MongoTestServer.TestMongoSessionIdManager)server1.getServer().getSessionIdManager()).cancelScavenge();

            //check that the session is idle
            checkSessionIdle();

            //wait until the session should be expired
            pause (inactivePeriod + (inactivePeriod/2));
            
            //make a request to try and deidle the session
            //make another request to de-idle the session
            request = client.newRequest(url + "?action=testfail");
            request.getHeaders().add("Cookie", sessionCookie);
            response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
        }
        finally
        {
            server1.stop();
        }
    }
    
    public void checkSessionIdle ()
    {
        assertNotNull(_servlet);
        assertNotNull((NoSqlSession)_servlet._session);
        assertTrue(((NoSqlSession)_servlet._session).isIdle());    
    }
    
    
    public void checkSessionDeIdle ()
    {
        assertNotNull(_servlet);
        assertNotNull((NoSqlSession)_servlet._session);
        assertTrue(!((NoSqlSession)_servlet._session).isIdle());  
        assertTrue(!((NoSqlSession)_servlet._session).isDeIdleFailed());
    }

    
    public void checkValue (int value)
    {
        assertNotNull(_servlet);
        assertEquals(value, ((Integer)_servlet._session.getAttribute("value")).intValue());
    }
    
    public static class TestServlet extends HttpServlet
    {
        public String originalId = null;
        
        public HttpSession _session = null;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("value", new Integer(1));
                originalId = session.getId();
                assertTrue(!((NoSqlSession)session).isIdle());
                _session = session;
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session != null);
                assertTrue(originalId.equals(session.getId()));
                assertTrue(!((NoSqlSession)session).isIdle());
                Integer v = (Integer)session.getAttribute("value");
                assertNotNull(v);
                session.setAttribute("value", new Integer(v.intValue()+1));
            }
            else if ("testfail".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session == null);
            }
        }
    }
}
