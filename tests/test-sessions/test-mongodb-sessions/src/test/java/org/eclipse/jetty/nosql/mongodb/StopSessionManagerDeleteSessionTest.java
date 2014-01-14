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
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Test;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;

public class StopSessionManagerDeleteSessionTest
{
    public MongoTestServer createServer(int port, int max, int scavenge)
    {
        MongoTestServer server =  new MongoTestServer(port,max,scavenge);
       
        return server;
    }
    
    /**
     * @throws Exception
     */
    @Test
    public void testStopSessionManagerDeleteSession() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        
        MongoTestServer server = createServer(0, 1, 0);
        ServletContextHandler context = server.addContext(contextPath);
        ServletHolder holder = new ServletHolder();
        TestServlet servlet = new TestServlet();
        holder.setServlet(servlet);
        
        context.addServlet(holder, servletMapping);
        
        MongoSessionManager sessionManager = (MongoSessionManager)context.getSessionHandler().getSessionManager();
        sessionManager.setPreserveOnStop(false);
        MongoSessionIdManager idManager = (MongoSessionIdManager)server.getServer().getSessionIdManager();
        idManager.setPurge(true);

        
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

                //stop the session manager
                sessionManager.stop();
                
                //check the database to see that the session has been marked invalid
                servlet.checkSessionInDB(false);
                
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
        String _id;

        public TestServlet() throws UnknownHostException, MongoException
        {
            super();            
            _sessions = new Mongo().getDB("HttpSessions").getCollection("sessions");
        }

        public void checkSessionInDB (boolean expectedValid)
        {
            DBObject dbSession = _sessions.findOne(new BasicDBObject("id", _id));
            assertTrue(dbSession != null);
            assertEquals(expectedValid, dbSession.get("valid"));
            if (!expectedValid)
                assertNotNull(dbSession.get(MongoSessionManager.__INVALIDATED));
        }

        public String getId()
        {
            return _id;
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
                _id = session.getId();
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
