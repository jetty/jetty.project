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
import static org.junit.Assert.assertNotEquals;
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
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

/**
 *  SaveIntervalTest
 *
 *  Checks to see that potentially stale sessions that have not
 *  changed are not always reloaded from the database.
 *
 *  This test is Ignored because it takes a little while to run.
 *
 */
public class SaveIntervalTest
{
    public static int INACTIVE = 90; //sec
    public static int SCAVENGE = 100; //sec
    public static int SAVE = 10; //sec


    @Ignore
    @Test
    public void testSaveInterval() throws Exception
    {
        AbstractTestServer server = new JdbcTestServer(0,INACTIVE,SCAVENGE, SessionCache.NEVER_EVICT);

        ServletContextHandler ctxA = server.addContext("/mod");
        ServletHolder holder = new ServletHolder();
        TestSaveIntervalServlet servlet = new TestSaveIntervalServlet();
        holder.setServlet(servlet);
        ctxA.addServlet(holder, "/test");


        //TODO set up the intermittent save

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
                long lastSaved = ((Session)servlet._session).getSessionData().getLastSaved();
                
                
                //do another request to change the session attribute
                Request request = client.newRequest("http://localhost:" + port + "/mod/test?action=set");
                request.header("Cookie", sessionCookie);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                long tmp = ((Session)servlet._session).getSessionData().getLastSaved();
                assertNotEquals(lastSaved, tmp); //set of attribute will cause save to db
                lastSaved = tmp;
                
                //do nothing for just a bit longer than the save interval to ensure
                //session will be checked against database on next request
                Thread.currentThread().sleep((SAVE+2)*1000);
             
                
                //do another request to access the session, this will cause session to be initially
                //checked against db. On exit of request, the access time will need updating, so the
                //session will be saved to db.
                request = client.newRequest("http://localhost:" + port + "/mod/test?action=tickle");
                request.header("Cookie", sessionCookie);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                tmp = ((Session)servlet._session).getSessionData().getLastSaved();
                assertNotEquals(lastSaved, tmp);
                lastSaved = tmp;
              
                //wait a little and do another request to access the session
                Thread.currentThread().sleep((SAVE/2)*1000);
                
                //do another request to access the session. This time, the save interval has not
                //expired, so we should NOT see a debug trace of loading stale session. Nor should
                //the exit of the request cause a save of the updated access time.
                request = client.newRequest("http://localhost:" + port + "/mod/test?action=tickle");
                request.header("Cookie", sessionCookie);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                tmp = ((Session)servlet._session).getSessionData().getLastSaved();
                assertEquals(lastSaved, tmp); //the save interval did not expire, so update to the access time will not have been persisted
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
    
    public static class TestSaveIntervalServlet extends HttpServlet
    {
        public HttpSession _session;
        
        
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            
            
            if ("create".equals(action))
            {
                HttpSession session = request.getSession(true);
                _session = session;
                return;
            }
            
            if ("set".equals(action))
            {
                HttpSession session = request.getSession(false);
                if (session == null)
                    throw new ServletException("Session is null for action=change");

                session.setAttribute("aaa", "12345");
                assertEquals(_session.getId(), session.getId());
                return;
            }
            
            if ("tickle".equals(action))
            {
                HttpSession session = request.getSession(false);
                if (session == null)
                    throw new ServletException("Session does not exist");

                assertEquals(_session.getId(), session.getId());
                return;
            }
        }
    }
    
}
