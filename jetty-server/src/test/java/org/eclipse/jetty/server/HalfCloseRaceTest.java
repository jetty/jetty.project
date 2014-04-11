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

package org.eclipse.jetty.server;
  
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.Socket;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.junit.Assert;
import org.junit.Test;

public class HalfCloseRaceTest
{
    @Test
    public void testHalfCloseRace() throws Exception
    {
        Server server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(0);
        connector.setMaxIdleTime(500);
        server.addConnector(connector);
        TestHandler handler = new TestHandler();
        server.setHandler(handler);

        server.start();
        
        Socket client = new Socket("localhost",connector.getLocalPort());
        
        int in = client.getInputStream().read();
        assertEquals(-1,in);
        
        client.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes());
        
        Thread.sleep(200);
        assertEquals(0,handler.getHandled());
        
    }

    public static class TestHandler extends AbstractHandler
    {
        transient int handled;
        
        public TestHandler()
        {
        }

        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            handled++;
            response.setContentType("text/html;charset=utf-8");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println("<h1>Test</h1>");
        }
        
        public int getHandled()
        {
            return handled;
        }
    }
}
