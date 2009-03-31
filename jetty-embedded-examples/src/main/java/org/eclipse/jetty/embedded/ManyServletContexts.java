// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.embedded;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class ManyServletContexts
{
    public static void main(String[] args)
        throws Exception
    {
        Server server = new Server(8080);
        
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);
        
        ServletContextHandler root = new ServletContextHandler(contexts,"/",ServletContextHandler.SESSIONS);
        root.addServlet(new ServletHolder(new HelloServlet("Ciao")), "/*");
        
        ServletContextHandler other = new ServletContextHandler(contexts,"/other",ServletContextHandler.SESSIONS);
        other.addServlet("org.eclipse.jetty.server.example.ManyServletServletContextHandlers$HelloServlet", "/*");
        
        StatisticsHandler stats = new StatisticsHandler();
        contexts.addHandler(stats);
        ServletContextHandler yetanother =new ServletContextHandler(stats,"/yo",ServletContextHandler.SESSIONS);
        yetanother.addServlet(new ServletHolder(new HelloServlet("YO!")), "/*");
        
        server.start();
        server.join();
    }

    public static class HelloServlet extends HttpServlet
    {
        String greeting="Hello";
        public HelloServlet()
        {}
        
        public HelloServlet(String hi)
        {greeting=hi;}
        
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println("<h1>"+greeting+" SimpleServlet</h1>");
            response.getWriter().println("session="+request.getSession(true).getId());
        }
    }
}
