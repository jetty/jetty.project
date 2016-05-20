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
import static org.junit.Assert.assertTrue;

import java.io.IOException;

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

/**
 * AbstractStopSessionManagerPreserveSessionTest
 *
 *
 */
public abstract class AbstractStopSessionManagerPreserveSessionTest extends AbstractTestBase
{
    public String _id;
    
    
    public abstract void checkSessionPersisted (String id, boolean expected);
    

    public abstract void configureSessionManagement(ServletContextHandler context);
    
    @Test
    public void testStopSessionManagerPreserveSession() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";
        
        AbstractTestServer server = createServer(0, -1,  AbstractTestServer.DEFAULT_SCAVENGE_SEC,  AbstractTestServer.DEFAULT_EVICTIONPOLICY);
        ServletContextHandler context = server.addContext(contextPath);
        ServletHolder holder = new ServletHolder();
        TestServlet servlet = new TestServlet();
        holder.setServlet(servlet);
        
        context.addServlet(holder, servletMapping);
     
        configureSessionManagement(context);
        
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

                //stop the session manager
                context.getSessionHandler().stop();
                
                //check the database to see that the session is still valid
                checkSessionPersisted(_id, true);
                
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
        }
    }
}
