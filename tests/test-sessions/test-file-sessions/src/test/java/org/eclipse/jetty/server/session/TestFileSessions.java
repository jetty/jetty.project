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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

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
    public void testSweep () throws Exception
    {
        int scavengePeriod = 2;      
        String contextPath = "/test";
        String servletMapping = "/server";
        int inactivePeriod = 5;
        int gracePeriod = 10;
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        FileSessionDataStoreFactory storeFactory = (FileSessionDataStoreFactory)createSessionDataStoreFactory();
        storeFactory.setGracePeriodSec(gracePeriod);
        TestServer server1 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
        
        try
        {
            server1.start();
            
            //create file not for our context that expired long ago and should be removed by sweep
            FileTestHelper.createFile("101_foobar_0.0.0.0_sessiona");
            FileTestHelper.assertSessionExists("sessiona", true);
            
            //create a file not for our context that is not expired and should be ignored
            String nonExpiredForeign = (System.currentTimeMillis()+TimeUnit.DAYS.toMillis(1))+"_foobar_0.0.0.0_sessionb";
            FileTestHelper.createFile(nonExpiredForeign);
            FileTestHelper.assertFileExists(nonExpiredForeign, true);
            
            //create a file not for our context that is recently expired, a thus ignored by sweep
            String expiredForeign = (System.currentTimeMillis()-TimeUnit.SECONDS.toMillis(1))+"_foobar_0.0.0.0_sessionc";
            FileTestHelper.createFile(expiredForeign);
            FileTestHelper.assertFileExists(expiredForeign, true);
            
            //create a file that is not a session file, it should be ignored
            FileTestHelper.createFile("whatever.txt");
            FileTestHelper.assertFileExists("whatever.txt", true);
            
            //create a file that is a non-expired session file for our context that should be ignored
            String nonExpired = (System.currentTimeMillis()+TimeUnit.DAYS.toMillis(1))+"_test_0.0.0.0_sessionb";
            FileTestHelper.createFile(nonExpired);
            FileTestHelper.assertFileExists(nonExpired, true);
            
            //create a file that is a never-expire session file for our context that should be ignored
            String neverExpired = "0_test_0.0.0.0_sessionc";
            FileTestHelper.createFile(neverExpired);
            FileTestHelper.assertFileExists(neverExpired, true);
            
            //create a file that is a never-expire session file for another context that should be ignored
            String foreignNeverExpired = "0_test_0.0.0.0_sessionc";
            FileTestHelper.createFile(foreignNeverExpired);
            FileTestHelper.assertFileExists(foreignNeverExpired, true);
            
            
            //need to wait to ensure scavenge runs so sweeper runs
            Thread.currentThread().sleep(2000L*scavengePeriod);
            FileTestHelper.assertSessionExists("sessiona", false);
            FileTestHelper.assertFileExists("whatever.txt", true);
            FileTestHelper.assertFileExists(nonExpired, true);
            FileTestHelper.assertFileExists(nonExpiredForeign, true);
            FileTestHelper.assertFileExists(expiredForeign, true);
            FileTestHelper.assertFileExists(neverExpired, true);
            FileTestHelper.assertFileExists(foreignNeverExpired, true);
        }
        finally
        {
            server1.stop();
        }
    }
    
    
    
    
    
  
    @Test
    public void test () throws Exception
    {
        String contextPath = "/test";
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
                FileTestHelper.assertSessionExists(TestServer.extractSessionId(sessionCookie), true);
                File file1 = FileTestHelper.getFile(TestServer.extractSessionId(sessionCookie));
                
                
                //request the session and check that the file for the session was changed
                Request request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=check");
                request.header("Cookie", sessionCookie);
                ContentResponse response2 = request.send();
                assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
                FileTestHelper.assertSessionExists(TestServer.extractSessionId(sessionCookie), true);
                File file2 = FileTestHelper.getFile(TestServer.extractSessionId(sessionCookie));
                
                assertFalse (file1.exists());
                assertTrue(file2.exists());
                
                //check expiry time in filename changed
                String tmp = file1.getName();
                tmp = tmp.substring(0,  tmp.indexOf("_"));
                
                long f1 = Long.valueOf(tmp);
                tmp = file2.getName();
                tmp = tmp.substring(0,  tmp.indexOf("_"));
                long f2 = Long.valueOf(tmp);
                assertTrue (f2>f1);
                
                //invalidate the session and verify that the session file is deleted
                request = client.newRequest("http://localhost:" + port1 + contextPath + servletMapping + "?action=remove");
                request.header("Cookie", sessionCookie);
                response2 = request.send();
                assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
                FileTestHelper.assertSessionExists(TestServer.extractSessionId(sessionCookie), false);
                
                //make another session
                response1 = client.GET("http://localhost:" + port1 + contextPath + servletMapping + "?action=init");
                assertEquals(HttpServletResponse.SC_OK,response1.getStatus());
                sessionCookie = response1.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
                FileTestHelper.assertSessionExists(TestServer.extractSessionId(sessionCookie), true);
                
                //wait for it to be scavenged
                Thread.currentThread().sleep((inactivePeriod + 2)*1000);
                FileTestHelper.assertSessionExists(TestServer.extractSessionId(sessionCookie), false);
                
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
            }
            else if ("check".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session != null);
                try {Thread.currentThread().sleep(1);}catch (Exception e) {e.printStackTrace();}
            }
        }
    }
    
    
}
