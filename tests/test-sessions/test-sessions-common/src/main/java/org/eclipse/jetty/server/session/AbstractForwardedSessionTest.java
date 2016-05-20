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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Test;


/**
 * AbstractForwardedSessionTest
 *
 * Test that creating a session inside a forward on the same context works, and that
 * attributes set after the forward returns are preserved.
 * 
 * This test requires that the sessions will be persisted, as the server is stopped and
 * then restarted in order to check that all the attributes were saved.
 */
public abstract class AbstractForwardedSessionTest extends AbstractTestBase
{

    
    @Test
    public void testSessionCreateInForward() throws Exception
    {
        AbstractTestServer testServer = createServer(0, AbstractTestServer.DEFAULT_MAX_INACTIVE,  AbstractTestServer.DEFAULT_SCAVENGE_SEC,  AbstractTestServer.DEFAULT_EVICTIONPOLICY);
        ServletContextHandler testServletContextHandler = testServer.addContext("/context");
        testServletContextHandler.addServlet(Servlet1.class, "/one");
        testServletContextHandler.addServlet(Servlet2.class, "/two");
        testServletContextHandler.addServlet(Servlet3.class, "/three");
        testServletContextHandler.addServlet(Servlet4.class, "/four");
       
      

        try
        {
            testServer.start();
            int serverPort=testServer.getPort();
            HttpClient client = new HttpClient();
            client.start();
            try
            {
                //make a request to the first servlet, which will forward it to other servlets
                ContentResponse response = client.GET("http://localhost:" + serverPort + "/context/one");
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                String sessionCookie = response.getHeaders().get("Set-Cookie");
                assertTrue(sessionCookie != null);
                // Mangle the cookie, replacing Path with $Path, etc.
                sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                //test that the session was created, and that it contains the attributes from servlet3 and servlet1
                
                //stop the server, to make sure any session persistence has happened
                testServer.stop();
                            
                //restart
                testServer.start();
                serverPort = testServer.getPort();
       
                //Make a fresh request
                Request request = client.newRequest("http://localhost:" + serverPort + "/context/four");
                request.header("Cookie", sessionCookie);
                response = request.send();
                assertEquals(HttpServletResponse.SC_OK, response.getStatus());
                
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            testServer.stop();
        }
        
    }
    

    public static class Servlet1 extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            //Don't create a session, just forward to another session in the same context
            assertNull(request.getSession(false));
            
            //The session will be created by the other servlet, so will exist as this dispatch returns
            RequestDispatcher dispatcher = request.getServletContext().getRequestDispatcher("/two");
            dispatcher.forward(request, response);
   
            HttpSession sess = request.getSession(false);
            assertNotNull(sess);
            assertNotNull(sess.getAttribute("servlet3"));
            sess.setAttribute("servlet1", "servlet1");
        }
    }

    public static class Servlet2 extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            //forward to yet another servlet to do the creation
            assertNull(request.getSession(false));

            RequestDispatcher dispatcher = request.getServletContext().getRequestDispatcher("/three");
            dispatcher.forward(request, response);
            
            //the session should exist after the forward
            HttpSession sess = request.getSession(false);
            assertNotNull(sess);
            assertNotNull(sess.getAttribute("servlet3"));
        }
    }



    public static class Servlet3 extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {    
            //No session yet
            assertNull(request.getSession(false));
            
            //Create it
            HttpSession session = request.getSession();
            assertNotNull(session);
            
            //Set an attribute on it
            session.setAttribute("servlet3", "servlet3");
        }
    }
    
    
    public static class Servlet4 extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {    
            //Check that the session contains attributes set during and after the session forward
            HttpSession session = request.getSession();
            assertNotNull(session);
            assertNotNull(session.getAttribute("servlet1"));
            assertNotNull(session.getAttribute("servlet3"));
        }
    }
    
}
