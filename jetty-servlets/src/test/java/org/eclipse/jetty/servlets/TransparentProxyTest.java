//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.servlets;


import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


/**
 * TransparentProxyTest
 *
 *
 */
public class TransparentProxyTest
{
  

        protected Server server;
        protected Server proxyServer;
        
        public static class ServletA extends HttpServlet {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
                resp.setContentType("text/plain");
                resp.getWriter().println("ok");
            }
        }
        
        @Before
        public void setUp () throws Exception
        {
            //set up the target server
            server = new Server();
            SelectChannelConnector connector = new SelectChannelConnector();
            connector.setPort(8080);
            server.addConnector(connector);
            ServletContextHandler handler = new ServletContextHandler(server, "/");
            handler.addServlet(ServletA.class, "/a");
            server.setHandler(handler);
            server.start();


            //set up the server that proxies to the target server
            proxyServer = new Server();
            SelectChannelConnector proxyConnector = new SelectChannelConnector();
            proxyConnector.setPort(8081);
            proxyServer.addConnector(proxyConnector);           
            ServletContextHandler proxyHandler = new ServletContextHandler(proxyServer, "/");
            proxyHandler.addServlet(new ServletHolder(new ProxyServlet.Transparent("/", "http", "127.0.0.1", 8080, "/")), "/");
            proxyServer.setHandler(proxyHandler);
            proxyServer.start();

        }
        
        
        @After
        public void tearDown() throws Exception
        {
            server.stop();
            proxyServer.stop();
        }


        @Test
        public void testDirectNoContentType() throws Exception
        {
            // Direct request without Content-Type set works
            URL url = new URL("http://localhost:8080/a");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            assertEquals(200, con.getResponseCode());
        }

        
        @Test
        public void testDirectWithContentType() throws Exception
        {
            // Direct request with Content-Type works
            URL url = new URL("http://localhost:8080/a");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.addRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf-8");
            assertEquals(200, con.getResponseCode());
        }

        @Test
        public void testProxiedWithoutContentType() throws Exception
        {
            // Proxied request without Content-Type set works
            URL url = new URL("http://localhost:8081/a");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            assertEquals(200, con.getResponseCode());
            System.err.println (con.getContentType());
        }

        @Test
        public void testProxiedWithContentType() throws Exception
        {
            // Proxied request with Content-Type set fails

            URL url = new URL("http://localhost:8081/a");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.addRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf-8");
            assertEquals(200, con.getResponseCode());
            System.err.println(con.getContentType());
            
        }
}
