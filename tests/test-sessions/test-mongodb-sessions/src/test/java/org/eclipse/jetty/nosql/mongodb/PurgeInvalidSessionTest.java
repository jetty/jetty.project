//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.net.UnknownHostException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Test;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;

/**
 * PurgeInvalidSessionTest
 *
 *
 *
 */
public class PurgeInvalidSessionTest
{
    public MongoTestServer createServer(int port, int max, int scavenge)
    {
        MongoTestServer server =  new MongoTestServer(port,max,scavenge);
       
        return server;
    }
    
    
    
    @Test
    public void testPurgeInvalidSession() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        long purgeDelay = 1000; //1 sec
        long purgeInvalidAge = 1000; //1 sec
        long purgeValidAge = 1000;

        //ensure scavenging is turned off so the purger gets a chance to find the session
        MongoTestServer server = createServer(0, 1, 0);
        ServletContextHandler context = server.addContext(contextPath);
        context.addServlet(TestServlet.class, servletMapping);

        MongoSessionManager sessionManager = (MongoSessionManager)context.getSessionHandler().getSessionManager();
        MongoSessionIdManager idManager = (MongoSessionIdManager)server.getServer().getSessionIdManager();
        idManager.setPurge(true);
        idManager.setPurgeDelay(purgeDelay); 
        idManager.setPurgeInvalidAge(purgeInvalidAge); //purge invalid sessions older than 
        idManager.setPurgeValidAge(purgeValidAge); //purge valid sessions older than
        
        
        
        server.start();
        int port=server.getPort();
        try
        {
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                //Create a session
                ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=create");
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                //make a request to invalidate the session
                Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=invalidate");
                request.header("Cookie", sessionCookie);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                
                Thread.currentThread().sleep(3*purgeDelay); //sleep long enough for purger to have run
                
                //make a request using previous session to test if its still there
                request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=test");
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


    @Test
    public void testPurgeInvalidSessionsWithLimit() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        long purgeInvalidAge = 1000; //1 sec
        int purgeLimit = 5; // only purge 5 sessions for each purge run

        //ensure scavenging is turned off so the purger gets a chance to find the session
        MongoTestServer server = createServer(0, 1, 0);
        ServletContextHandler context = server.addContext(contextPath);
        context.addServlet(TestServlet.class, servletMapping);

        // disable purging so we can call it manually below
        MongoSessionManager sessionManager = (MongoSessionManager)context.getSessionHandler().getSessionManager();
        MongoSessionIdManager idManager = (MongoSessionIdManager)server.getServer().getSessionIdManager();
        idManager.setPurge(false);
        idManager.setPurgeLimit(purgeLimit);
        idManager.setPurgeInvalidAge(purgeInvalidAge);
        // don't purge valid sessions
        idManager.setPurgeValidAge(0);


        server.start();
        int port=server.getPort();
        try
        {
            // cleanup any previous sessions that are invalid so that we are starting fresh
            idManager.purgeFully();
            long sessionCountAtTestStart = sessionManager.getSessionStoreCount();

            HttpClient client = new HttpClient();
            client.start();
            try
            {
                // create double the purge limit of sessions, and make them all invalid
                for (int i = 0; i < purgeLimit * 2; i++)
                {
                    ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=create");
                    assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                    String sessionCookie = response.getHeaders().get("Set-Cookie");
                    assertTrue(sessionCookie != null);
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");


                    Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=invalidate");
                    request.header("Cookie", sessionCookie);
                    response = request.send();
                    assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                }

                // sleep for our invalid age period so that the purge below does something
                Thread.sleep(purgeInvalidAge * 2);

                // validate that we have the right number of sessions before we purge
                assertEquals("Expected to find right number of sessions before purge", sessionCountAtTestStart + (purgeLimit * 2), sessionManager.getSessionStoreCount());

                // run our purge we should still have items in the DB
                idManager.purge();
                assertEquals("Expected to find sessions remaining in db after purge run with limit set",
                        sessionCountAtTestStart + purgeLimit, sessionManager.getSessionStoreCount());
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
        DBCollection _sessions;


        public TestServlet() throws UnknownHostException, MongoException
        {
            super();            
            _sessions = new Mongo().getDB("HttpSessions").getCollection("sessions");
        }

        
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("create".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("foo", "bar");
                assertTrue(session.isNew());
            }
            else if ("invalidate".equals(action))
            {  
                HttpSession existingSession = request.getSession(false);
                assertNotNull(existingSession);
                String id = existingSession.getId();
                id = (id.indexOf(".") > 0?id.substring(0, id.indexOf(".")):id);
                DBObject dbSession = _sessions.findOne(new BasicDBObject("id",id)); 
                assertNotNull(dbSession);
                
                existingSession.invalidate();
                
                //still in db, just marked as invalid
                dbSession = _sessions.findOne(new BasicDBObject("id", id));       
                assertNotNull(dbSession);
                assertTrue(dbSession.containsField(MongoSessionManager.__INVALIDATED));
                assertTrue(dbSession.containsField(MongoSessionManager.__VALID));
                assertTrue(dbSession.get(MongoSessionManager.__VALID).equals(false));
            }
            else if ("test".equals(action))
            {
                String id = request.getRequestedSessionId();
                assertNotNull(id);
       
                id = (id.indexOf(".") > 0?id.substring(0, id.indexOf(".")):id);
  
                HttpSession existingSession = request.getSession(false);
                assertTrue(existingSession == null);
                
                //not in db any more
                DBObject dbSession = _sessions.findOne(new BasicDBObject("id", id));
                assertTrue(dbSession == null);
            }
        }
    }
}
