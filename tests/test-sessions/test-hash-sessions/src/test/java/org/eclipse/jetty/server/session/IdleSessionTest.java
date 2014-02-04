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
import static org.junit.Assert.assertNotNull;
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
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.Test;


/**
 * IdleSessionTest
 *
 * Checks that a session can be idled and de-idled on the next request if it hasn't expired.
 *
 */
public class IdleSessionTest
{
    
    public class IdleHashTestServer extends HashTestServer
    {
        private int _idlePeriod;
        private File _storeDir;

        public IdleHashTestServer(int port, int maxInactivePeriod, int scavengePeriod, int idlePeriod, File storeDir)
        {
            super(port, maxInactivePeriod, scavengePeriod);
            _idlePeriod = idlePeriod;
            _storeDir = storeDir;
        }

        @Override
        public SessionManager newSessionManager()
        {
            try
            {
                HashSessionManager manager = (HashSessionManager)super.newSessionManager();
                manager.setStoreDirectory(_storeDir);
                manager.setIdleSavePeriod(_idlePeriod);
                return manager;
            }
            catch ( IOException e)
            {
                return null;
            }
        }



    }

    public  HashTestServer createServer(int port, int max, int scavenge, int idle, File storeDir)
    {
        return new IdleHashTestServer(port, max, scavenge, idle, storeDir);
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
    public void testSessionIdle() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 200;
        int scavengePeriod = 3;
        int idlePeriod = 5;
        ((StdErrLog)Log.getLogger(org.eclipse.jetty.server.session.HashedSession.class)).setHideStacks(true);
        System.setProperty("org.eclipse.jetty.STACKS", "false");
        File storeDir = new File (System.getProperty("java.io.tmpdir"), "idle-test");
        storeDir.deleteOnExit();

        HashTestServer server1 = createServer(0, inactivePeriod, scavengePeriod, idlePeriod, storeDir);
        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        ServletContextHandler contextHandler = server1.addContext(contextPath);
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
            String sessionCookie = response.getHeaders().getStringField("Set-Cookie");
            assertTrue(sessionCookie != null);
            // Mangle the cookie, replacing Path with $Path, etc.
            sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

            //and wait until the session should be idled out
            pause(idlePeriod * 2);

            //check that the file exists
            checkSessionIdled(storeDir, getSessionId(sessionCookie));

            //make another request to de-idle the session
            Request request = client.newRequest(url + "?action=test");
            request.getHeaders().add("Cookie", sessionCookie);
            ContentResponse response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());

            //check session de-idled
            checkSessionDeIdled(storeDir);

            //wait again for the session to be idled
            pause(idlePeriod * 2);
            
            //check that it is
            checkSessionIdled(storeDir, getSessionId(sessionCookie));
            
          
            //delete the file
            File idleFile = getIdleFile(storeDir, getSessionId(sessionCookie));
            assertTrue(idleFile.exists());
            assertTrue(idleFile.delete());
            
            //make a request
            request = client.newRequest(url + "?action=testfail");
            request.getHeaders().add("Cookie", sessionCookie);
            response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
        }
        finally
        {
            server1.stop();
            IO.delete(storeDir);
        }
    }


    public void checkSessionIdled (File sessionDir, String sessionId)
    {
        assertNotNull(sessionDir);
        assertTrue(sessionDir.exists());
        String[] files = sessionDir.list();
        assertNotNull(files);
        assertEquals(1, files.length);
        assertEquals(sessionId, files[0]);
    }


    public void checkSessionDeIdled (File sessionDir)
    {
        assertNotNull(sessionDir);
        assertTrue(sessionDir.exists());
        String[] files = sessionDir.list();
        assertNotNull(files);
        assertEquals(0, files.length);
    }
    
    public File getIdleFile (File sessionDir, String sessionId)
    {
        assertNotNull(sessionDir);
        assertTrue(sessionDir.exists());
        String[] files = sessionDir.list();
        assertNotNull(files);      
        return new File(sessionDir, files[0]);
    }

    public String getSessionId (String sessionCookie)
    {
        assertNotNull(sessionCookie);
        String sessionId = sessionCookie.substring(11);
        sessionId = sessionId.substring(0, sessionId.indexOf(';'));
        return sessionId;
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
                assertTrue(!((HashedSession)session).isIdled());
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session != null);
                assertTrue(originalId.equals(session.getId()));
                assertEquals("test", session.getAttribute("test"));
                assertTrue(!((HashedSession)session).isIdled());
            }
            else if ("testfail".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session == null);
            }
        }
    }
}
