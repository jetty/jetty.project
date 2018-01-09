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


package org.eclipse.jetty.server.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * TestFileSessions
 *
 *
 */
public class TestFileSessions extends AbstractTestBase
{
    @Before
    public void before() throws Exception
    {
       FileTestHelper.setup();
    }
    
    @After 
    public void after()
    {
       FileTestHelper.teardown();
    }
 

    /** 
     * @see org.eclipse.jetty.server.session.AbstractTestBase#createSessionDataStoreFactory()
     */
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return FileTestHelper.newSessionDataStoreFactory();
    }
  
    @Test
    public void test () throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 5;
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory = createSessionDataStoreFactory();
        TestServer server1 = new TestServer(0, inactivePeriod, 2, cacheFactory, storeFactory);
        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
        try
        {
            server1.start();
            int port1 = server1.getPort();
            
            HttpClient client = new HttpClient();
            client.start();
            
            try
            {
                // Connect to server1 to create a session and get its session cookie
                ContentResponse response1 = client.GET("http://localhost:" + port1 + contextPath + servletMapping + "?action=init");
                assertEquals(HttpServletResponse.SC_OK,response1.getStatus());
                String sessionCookie = response1.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
                
                //check that the file for the session exists after creating the session
                FileTestHelper.assertFileExists(TestServer.extractSessionId(sessionCookie), true);
                File file1 = FileTestHelper.getFile(TestServer.extractSessionId(sessionCookie));
                
                
                //request the session and check that the file for the session exists with an updated lastmodify
                Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=check");
                request.header("Cookie", sessionCookie);
                ContentResponse response2 = request.send();
                assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
                FileTestHelper.assertFileExists(TestServer.extractSessionId(sessionCookie), true);
                File file2 = FileTestHelper.getFile(TestServer.extractSessionId(sessionCookie));
                assertTrue (!file1.equals(file2));
                assertTrue (file2.lastModified() > file1.lastModified());
                
                //invalidate the session and verify that the session file is deleted
                request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=remove");
                request.header("Cookie", sessionCookie);
                response2 = request.send();
                assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
                FileTestHelper.assertFileExists(TestServer.extractSessionId(sessionCookie), false);
                
                //make another session
                response1 = client.GET("http://localhost:" + port1 + contextPath + servletMapping + "?action=init");
                assertEquals(HttpServletResponse.SC_OK,response1.getStatus());
                sessionCookie = response1.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
                FileTestHelper.assertFileExists(TestServer.extractSessionId(sessionCookie), true);
                
                //wait for it to be scavenged
                Thread.currentThread().sleep((inactivePeriod + 2)*1000);
                FileTestHelper.assertFileExists(TestServer.extractSessionId(sessionCookie), false);
                
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            server1.stop();
        }
    }
    
    public static class TestServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("A", "A");
            }
            else if ("remove".equals(action))
            {
                HttpSession session = request.getSession(false);
                session.invalidate();
                //assertTrue(session == null);
            }
            else if ("check".equals(action))
            {
                HttpSession session = request.getSession(false);
            }
        }
    }
    
    
}
