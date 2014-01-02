//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.net.UnknownHostException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;


import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Test;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;

/**
 * PurgeValidSessionTest
 *
 *
 *
 */
public class PurgeValidSessionTest
{



    public MongoTestServer createServer(int port, int max, int scavenge)
    {
        MongoTestServer server =  new MongoTestServer(port,max,scavenge);

        return server;
    }



    @Test
    public void testPurgeValidSession() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        long purgeDelay = 1000; //1 sec
        long purgeValidAge = 1000;

        //ensure scavenging is turned off so the purger gets a chance to find the session
        MongoTestServer server = createServer(0, 1, 0);
        ServletContextHandler context = server.addContext(contextPath);
        context.addServlet(TestServlet.class, servletMapping);

        MongoSessionManager sessionManager = (MongoSessionManager)context.getSessionHandler().getSessionManager();
        MongoSessionIdManager idManager = (MongoSessionIdManager)server.getServer().getSessionIdManager();
        idManager.setPurge(true);
        idManager.setPurgeDelay(purgeDelay); 

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
                String sessionCookie = response.getHeaders().getStringField("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");


                Thread.currentThread().sleep(3*purgeDelay); //sleep long enough for purger to have run

                //make a request using previous session to test if its still there               
                Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=test");
                request.header("Cookie", sessionCookie);
                ContentResponse response2 = request.send();
                assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
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
                assertTrue(session.isNew());
            }
            else if ("test".equals(action))
            {
                String id = request.getRequestedSessionId();
                assertNotNull(id);
                id = id.substring(0, id.indexOf("."));

                HttpSession existingSession = request.getSession(false);
                assertTrue(existingSession == null);

                //not in db any more
                DBObject dbSession = _sessions.findOne(new BasicDBObject("id", id));
                assertTrue(dbSession == null);
            }
        }
    }

}
