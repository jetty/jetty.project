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

package org.eclipse.jetty.servlet;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class SendErrorTest
{
    @SuppressWarnings("serial")
    public static class SendErrorServlet extends HttpServlet
    {
        private final int code;
        private final String reason;
        
        public SendErrorServlet(int code, String reason)
        {
            this.code = code;
            this.reason = reason;
        }
        
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.sendError(code, reason);
        }
    }
    
    @Parameters
    public static List<Object[]> data()
    {
        List<Object[]> cases = new ArrayList<Object[]>();
        
        cases.add(new Object[]{ 400, "Bad Client Request", "Bad Client Request" });
        cases.add(new Object[]{ 500, "Bad\rReason\nMessage", "Bad Reason Message" });
        cases.add(new Object[]{ 501, "Bad\rReason\u560AMessage", "Bad Reason?Message" });
        cases.add(new Object[]{ 501, "Bad\u0A0DReason\u0D0AMessage", "Bad?Reason?Message" });
        
        return cases;
    }
    
    @Parameter(0)
    public int code;
    
    @Parameter(1)
    public String rawReason;
    
    @Parameter(2)
    public String expectedReason;
    
    @Test
    public void init() throws Exception
    {
        Server server = new Server();
        try {
            LocalConnector connector = new LocalConnector();
            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SECURITY|ServletContextHandler.NO_SESSIONS);
    
            server.setSendServerVersion(false);
            server.addConnector(connector);
            server.setHandler(context);
    
            context.setContextPath("/");
    
            context.addServlet(DefaultServlet.class, "/");
            
            ServletHolder holder = new ServletHolder(new SendErrorServlet(code,rawReason));
            context.addServlet(holder, "/error/*");
            
            server.start();
            
            // Perform tests
            StringBuilder req = new StringBuilder();
            req.append("GET /error/").append(code).append(" HTTP/1.1\r\n");
            req.append("Host: local\r\n");
            req.append("Connection: close\r\n");
            req.append("\r\n");
            
            String response = connector.getResponses(req.toString());
            assertThat("Response", response, containsString("HTTP/1.1 " + code + " " + expectedReason));
            
        } finally {
            server.stop();
        }
    }
}
