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
import static org.junit.Assert.assertFalse;
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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.Locker.Lock;
import org.junit.Test;


/**
 * IdleSessionTest
 *
 * Checks that a session can be idled and de-idled on the next request if it hasn't expired.
 * 
 *
 *
 */
public abstract class AbstractIdleSessionTest extends AbstractTestBase
{

    protected TestServlet _servlet = new TestServlet();
    protected AbstractTestServer _server1 = null;
 

    /**
     * @param sessionId
     */
    public abstract void checkSessionIdled (String sessionId);



    /**
     * @param sessionId
     */
    public abstract void checkSessionDeIdled (String sessionId);
    
    
 
    
    /**
     * @param sessionId
     */
    public abstract void deleteSessionData (String sessionId);


    /**
     * @param sec
     */
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

    /**
     * @throws Exception
     */
    @Test
    public void testSessionIdle() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        int inactivePeriod = 20;
        int scavengePeriod = 3;
        int evictionSec = 5;
  


        _server1 = createServer(0, inactivePeriod, scavengePeriod, evictionSec);
        ServletHolder holder = new ServletHolder(_servlet);
        ServletContextHandler contextHandler = _server1.addContext(contextPath);
        contextHandler.addServlet(holder, servletMapping);
        _server1.start();
        int port1 = _server1.getPort();

        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
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
            pause(evictionSec*3);

            //check that the session has been idled
            checkSessionIdled(AbstractTestServer.extractSessionId(sessionCookie));

            //make another request to de-idle the session
            Request request = client.newRequest(url + "?action=test");
            request.getHeaders().add("Cookie", sessionCookie);
            ContentResponse response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());

            //check session de-idled
            checkSessionDeIdled(AbstractTestServer.extractSessionId(sessionCookie));
            
            //wait again for the session to be idled
            pause(evictionSec*3);
            
            //check that it is
            checkSessionIdled(AbstractTestServer.extractSessionId(sessionCookie));

            //While idle, take some action to ensure that a deidle won't work, like
            //deleting all sessions in mongo
            deleteSessionData(AbstractTestServer.extractSessionId(sessionCookie));

            //make a request
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
            pause(evictionSec * 3);

            //stop the scavenger
            if (_server1.getHouseKeeper() != null)
                _server1.getHouseKeeper().stop();
            
            //check that the session is idle
            checkSessionIdled(AbstractTestServer.extractSessionId(sessionCookie));

            //wait until the session should be expired
            pause (inactivePeriod + (3*scavengePeriod));

            //make another request to de-idle the session
            request = client.newRequest(url + "?action=testfail");
            request.getHeaders().add("Cookie", sessionCookie);
            response2 = request.send();
            assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
        }
        finally
        {
            _server1.stop();
        }
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
                Session s = (Session)session;
                try (Lock lock = s.lock())
                {
                    assertTrue(s.isResident());
                }
                _session = s;
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session != null);
                assertTrue(originalId.equals(session.getId()));
                Session s = (Session)session;
                try (Lock lock = s.lock();)
                {
                   assertTrue(s.isResident());
                }
                Integer v = (Integer)session.getAttribute("value");
                session.setAttribute("value", new Integer(v.intValue()+1));
                _session = session;
            }
            else if ("testfail".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session == null);
                _session = session;
            }
        }
    }
}
