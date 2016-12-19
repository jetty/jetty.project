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
import org.eclipse.jetty.server.session.AbstractSessionExpiryTest;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.StringUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;




public class SessionExpiryTest extends AbstractSessionExpiryTest
{

    
    @BeforeClass
    public static void beforeClass() throws Exception
    {
        MongoTestServer.dropCollection();
        MongoTestServer.createCollection();
    }

    @AfterClass
    public static void afterClass() throws Exception
    {
        MongoTestServer.dropCollection();
    }
    
    @Override
    public AbstractTestServer createServer(int port, int max, int scavenge, int evictionPolicy) throws Exception
    {
       return new MongoTestServer(port,max,scavenge, evictionPolicy);
    }

    @Test
    public void testSessionNotExpired() throws Exception
    {
        super.testSessionNotExpired();
    }
    
    @Test
    public void testSessionExpiry() throws Exception
    {
        super.testSessionExpiry();
    }
    
    @Test
    public void testRequestForSessionWithChangedTimeout() throws Exception
    {
        super.testRequestForSessionWithChangedTimeout();
    }
    
    @Test
    public void testBigSessionExpiry() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = Integer.MAX_VALUE * 60; //integer overflow
        int scavengePeriod = 10;
        int idlePassivatePeriod = 0;
        AbstractTestServer server1 = createServer(0, inactivePeriod, scavengePeriod, idlePassivatePeriod);
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

            //make a request to set up a session on the server
            ContentResponse response1 = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK,response1.getStatus());
            String sessionCookie = response1.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            // Mangle the cookie, replacing Path with $Path, etc.
            sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
            
            String sessionId = AbstractTestServer.extractSessionId(sessionCookie);     
            
            DBCollection sessions = MongoTestServer.getCollection();
            verifySessionCreated(listener,sessionId);
            //verify that the session timeout is set in mongo
            verifySessionTimeout(sessions, sessionId, -1); //SessionManager sets -1 if maxInactive < 0
            
            //get the session expiry time from mongo
            long expiry = getSessionExpiry(sessions, sessionId);
            assertEquals(0, expiry);

        }
        finally
        {
            server1.stop();
        } 
    }
    
    @Test
    public void changeSessionTimeout() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 10;
        int scavengePeriod = 1;
        int idlePassivatePeriod = 0;
        AbstractTestServer server1 = createServer(0, inactivePeriod, scavengePeriod, idlePassivatePeriod);
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

            //make a request to set up a session on the server
            ContentResponse response1 = client.GET(url + "?action=init");
            assertEquals(HttpServletResponse.SC_OK,response1.getStatus());
            String sessionCookie = response1.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            // Mangle the cookie, replacing Path with $Path, etc.
            sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
            
            String sessionId = AbstractTestServer.extractSessionId(sessionCookie);     
            
            DBCollection sessions = MongoTestServer.getCollection();
            verifySessionCreated(listener,sessionId);
            //verify that the session timeout is set in mongo
            verifySessionTimeout(sessions, sessionId, inactivePeriod);
            
            //get the session expiry time from mongo
            long expiry = getSessionExpiry(sessions, sessionId);
            //make another request to change the session timeout to a smaller value
            inactivePeriod = 5;
            Request request = client.newRequest(url + "?action=change&val="+inactivePeriod);
            request.getHeaders().add("Cookie", sessionCookie);
            ContentResponse response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
 
            
            //check the timeout in mongo
            verifySessionTimeout(sessions, sessionId, inactivePeriod);
            //check the session expiry time has decreased from previous value
            assertTrue(getSessionExpiry(sessions, sessionId)<expiry);
            expiry = getSessionExpiry(sessions, sessionId);
            
            //increase the session timeout
            inactivePeriod = 20;
            request = client.newRequest(url + "?action=change&val="+inactivePeriod);
            request.getHeaders().add("Cookie", sessionCookie);
            response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
            //verify that the session timeout is set in mongo
            verifySessionTimeout(sessions, sessionId, inactivePeriod);
            long latestExpiry = getSessionExpiry(sessions, sessionId);
            assertTrue (latestExpiry > expiry);       
            assertTrue(getSessionAccessed(sessions, sessionId)+ (1000L*inactivePeriod) <= getSessionExpiry(sessions, sessionId));  
            assertTrue (latestExpiry >= 15);//old inactive expired in 5, new inactive expired in 20
        }
        finally
        {
            server1.stop();
        }     
    }
    
    
    @Test
    public void testChangeNewSessionTimeout () throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 10;
        int scavengePeriod = 1;
        int inspectPeriod = 1;
        int idlePassivatePeriod = 0;
        AbstractTestServer server1 = createServer(0, inactivePeriod, scavengePeriod,idlePassivatePeriod);
        ImmediateChangeTimeoutServlet servlet = new ImmediateChangeTimeoutServlet();
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

            inactivePeriod = 5; //change from the sessionmanager configured default
            
            //make a request to set up a session on the server and change its inactive setting straight away
            ContentResponse response1 = client.GET(url + "?action=init&val="+inactivePeriod);
            assertEquals(HttpServletResponse.SC_OK,response1.getStatus());
            String sessionCookie = response1.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);
            // Mangle the cookie, replacing Path with $Path, etc.
            sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
            
            String sessionId = AbstractTestServer.extractSessionId(sessionCookie);     
            
            DBCollection sessions = MongoTestServer.getCollection();
            verifySessionCreated(listener,sessionId);
            //verify that the session timeout is the new value and not the default
            verifySessionTimeout(sessions, sessionId, inactivePeriod);             
        }
        finally
        {
            server1.stop();
        }     
    }
    

    
    
    public void verifySessionTimeout (DBCollection sessions, String id, int sec) throws Exception
    {
        long val;
        
        if (sec > 0)
            val = sec*1000L;
        else
            val = sec;
        
        assertNotNull(sessions);
        assertNotNull(id);
        
        DBObject o = sessions.findOne(new BasicDBObject(MongoSessionDataStore.__ID,id));
        assertNotNull(o);
        Long maxIdle = (Long)o.get(MongoSessionDataStore.__MAX_IDLE);
        assertNotNull(maxIdle);
        assertEquals(val, maxIdle.longValue());
    }
    
    public long getSessionExpiry (DBCollection sessions, String id) throws Exception
    {
        assertNotNull(sessions);
        assertNotNull(id);
        
        DBObject o = sessions.findOne(new BasicDBObject(MongoSessionDataStore.__ID,id));
        assertNotNull(o);
        Long expiry = (Long)o.get(MongoSessionDataStore.__EXPIRY);
        return (expiry == null? null : expiry.longValue());
    }
    
    public long getSessionMaxInactiveInterval (DBCollection sessions, String id) throws Exception
    {
        assertNotNull(sessions);
        assertNotNull(id);
        
        DBObject o = sessions.findOne(new BasicDBObject(MongoSessionDataStore.__ID,id));
        assertNotNull(o);
        Long inactiveInterval = (Long)o.get(MongoSessionDataStore.__MAX_IDLE);
        return (inactiveInterval == null? null : inactiveInterval.longValue());
    }
    
    public long getSessionAccessed (DBCollection sessions, String id) throws Exception
    {
        assertNotNull(sessions);
        assertNotNull(id);
        
        DBObject o = sessions.findOne(new BasicDBObject(MongoSessionDataStore.__ID,id));
        assertNotNull(o);
        Long accessed = (Long)o.get(MongoSessionDataStore.__ACCESSED);
        return (accessed == null? null : accessed.longValue());
    }
    
    public void debugPrint (DBCollection sessions, String id) throws Exception
    {
        assertNotNull(sessions);
        assertNotNull(id);
        
        DBObject o = sessions.findOne(new BasicDBObject(MongoSessionDataStore.__ID,id));
        assertNotNull(o);
        System.err.println(o);
    }
    

    public static class ImmediateChangeTimeoutServlet extends HttpServlet
    {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                assertNotNull(session);
                String tmp = request.getParameter("val");
                int val = (StringUtil.isBlank(tmp)?0:Integer.valueOf(tmp.trim()));
                session.setMaxInactiveInterval(val);
            }
            else if ("change".equals(action))
            {
                String tmp = request.getParameter("val");
                int val = (StringUtil.isBlank(tmp)?0:Integer.valueOf(tmp.trim()));
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                session.setMaxInactiveInterval(val);
            }
        }
    }

}
