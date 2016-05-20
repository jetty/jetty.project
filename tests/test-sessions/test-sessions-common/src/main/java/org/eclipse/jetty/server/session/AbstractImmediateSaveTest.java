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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import org.junit.Test;


/**
 * AbstractImmediateSaveTest
 *
 *
 */
public abstract class AbstractImmediateSaveTest extends AbstractTestBase
{
    protected ServletContextHandler _context;
    
    
    public void checkSessionSaved (String id) throws Exception
    {
        assertTrue(_context.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));
    }


    public void pause(int scavenge)
    {
        try
        {
            Thread.sleep(scavenge * 1000L);
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    @Test
    public void testSaveNewSession() throws Exception
    {
        String servletMapping = "/server";
        int scavengePeriod = 3;
        int maxInactivePeriod = -1;
        AbstractTestServer server = createServer(0, maxInactivePeriod, scavengePeriod, SessionCache.NEVER_EVICT);
        _context = server.addContext("/");
        ServletHolder h = new ServletHolder();
        h.setServlet(new TestServlet());
        _context.addServlet(h, servletMapping);
        String contextPath = "";

        try
        {
            server.start();
            int port=server.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=create");
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
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
    public void testSaveNewSessionWithEviction() throws Exception
    {
        String servletMapping = "/server";
        int scavengePeriod = 3;
        int maxInactivePeriod = 1;
        AbstractTestServer server = createServer(0, maxInactivePeriod, scavengePeriod, SessionCache.EVICT_ON_SESSION_EXIT);
        _context = server.addContext("/");
        ServletHolder h = new ServletHolder();
        h.setServlet(new TestServlet());
        _context.addServlet(h, servletMapping);
        String contextPath = "";

        try
        {
            server.start();
            int port=server.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                //make request to make a save-on-create session
                ContentResponse response = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=create");
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                //session should now be evicted from the cache
                assertFalse(_context.getSessionHandler().getSessionCache().contains(AbstractTestServer.extractSessionId(sessionCookie)));
                
                //make another request for the same session
                Request request = client.newRequest("http://localhost:" + port + contextPath + servletMapping + "?action=test");
                request.header("Cookie", sessionCookie);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                
                //session should now be evicted from the cache again
                assertFalse(_context.getSessionHandler().getSessionCache().contains(AbstractTestServer.extractSessionId(sessionCookie)));
                
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
    
    public class TestServlet extends HttpServlet
    {
        String id;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("create".equals(action))
            {
                HttpSession session = request.getSession(true);
                assertNotNull(session);
                try
                {
                    checkSessionSaved(session.getId());
                }
                catch (Exception e)
                {
                    fail(e.getMessage());
                }
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
            }
        }
    }
}
