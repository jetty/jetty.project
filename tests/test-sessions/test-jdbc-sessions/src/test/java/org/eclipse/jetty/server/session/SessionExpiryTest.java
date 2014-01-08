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

package org.eclipse.jetty.server.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Test;

/**
 * SessionExpiryTest
 *
 *
 *
 */
public class SessionExpiryTest extends AbstractSessionExpiryTest
{

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
     * @see org.eclipse.jetty.server.session.AbstractSessionExpiryTest#createServer(int, int, int)
     */
    @Override
    public AbstractTestServer createServer(int port, int max, int scavenge)
    {
        return new JdbcTestServer(port,max,scavenge);
    }

    @Test
    public void testSessionExpiry() throws Exception
    {
     
        
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 2;
        int scavengePeriod = 1;
        AbstractTestServer server1 = createServer(0, inactivePeriod, scavengePeriod);
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
            String sessionCookie = response1.getHeaders().getStringField("Set-Cookie");
            assertTrue(sessionCookie != null);
            // Mangle the cookie, replacing Path with $Path, etc.
            sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
            
            String sessionId = extractSessionId(sessionCookie);     
            
            assertTrue(listener.createdSessions.contains(sessionId));
            //now stop the server
            server1.stop();

            //and wait until the expiry time has passed
            pause(inactivePeriod);

            //restart the server
            server1.start();
            
            port1 = server1.getPort();
            url = "http://localhost:" + port1 + contextPath + servletMapping;

            //make another request, the session should have expired
            Request request = client.newRequest(url + "?action=test");
            request.getHeaders().add("Cookie", sessionCookie);
            ContentResponse response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
            
            //and wait until the expiry time has passed
            pause(inactivePeriod);
            
            assertTrue(listener.destroyedSessions.contains(sessionId));
        }
        finally
        {
            server1.stop();
        }     
    }
  
    
    
    
    
    @Test
    public void testSessionNotExpired() throws Exception
    {
        super.testSessionNotExpired();
    }

    
    
    public String extractSessionId (String sessionCookie)
    {
        if (sessionCookie == null)
            return null;
        sessionCookie = sessionCookie.trim();
        int i = sessionCookie.indexOf(';');
        if (i >= 0)
            sessionCookie = sessionCookie.substring(0,i);
        if (sessionCookie.startsWith("JSESSIONID"))
            sessionCookie = sessionCookie.substring("JSESSIONID=".length());
        i = sessionCookie.indexOf('.');
        if (i >=0)
            sessionCookie = sessionCookie.substring(0,i);
        return sessionCookie;
    }

    
    
    @After
    public void tearDown() throws Exception 
    {
        try
        {
            DriverManager.getConnection( "jdbc:derby:sessions;shutdown=true" );
        }
        catch( SQLException expected )
        {
        }
    }
}
