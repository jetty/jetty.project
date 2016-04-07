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
import java.io.PrintWriter;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


/**
 * MaxInactiveMigrationTest
 *
 * Test
 */
public class MaxInactiveMigrationTest
{
    private JdbcTestServer testServer1;
    private JdbcTestServer testServer2;
    private HttpClient client;
    private String sessionCookie;

    @Test
    public void testFailover() throws Exception {
        String response1 = sendRequest( testServer1 );
        String response2 = sendRequest( testServer2 );

        assertEquals( "Hello World 1", response1 );
        assertEquals( "Hello World 2", response2 );
    }

    @Before
    public void setUp() throws Exception {
        testServer1 = new JdbcTestServer(0, -1, 2, -1);
        testServer2 = new JdbcTestServer(0, -1, 2, -1);
        ServletContextHandler context = testServer1.addContext("");
        context.addServlet(TestServlet.class, "/test");
        ServletContextHandler context2 = testServer2.addContext("");
        context2.addServlet(TestServlet.class, "/test");
        testServer1.start();
        testServer2.start();
        client = new HttpClient();
        client.start();
    }

    @After
    public void tearDown() throws Exception {

        testServer1.stop();
        testServer2.stop();
        client.stop();

        JdbcTestServer.shutdown(null);
    }



    private String sendRequest( JdbcTestServer server ) throws Exception {

        int port=server.getPort();

        //Log.getLog().setDebugEnabled(true);
        Request request = client.newRequest("http://localhost:" + port + "" + "/test");
        if (sessionCookie != null)
            request.header("Cookie", sessionCookie);
        ContentResponse response = request.send();
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());

        sessionCookie = response.getHeaders().get("Set-Cookie");
        assertTrue( sessionCookie != null );
        // Mangle the cookie, replacing Path with $Path, etc.
        sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

        return response.getContentAsString();
    }



    public static class TestServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
        private static final String ATTR_COUNTER = "counter";

        protected void doGet( HttpServletRequest request, HttpServletResponse response )
        throws IOException
        {
            HttpSession session = request.getSession( true );
            Integer counter = ( Integer )session.getAttribute( ATTR_COUNTER );
            if( counter == null ) {
                counter = 0;
            }
            counter = counter + 1;
            session.setAttribute( ATTR_COUNTER, counter );
            PrintWriter writer = response.getWriter();
            writer.write( "Hello World " + counter);
            writer.flush();
        }

        public String getServletInfo() {
            return "Test Servlet";
        }
    }

}
