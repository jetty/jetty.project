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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;


/* ------------------------------------------------------------ */
/**
 * A Jetty server can have multiple connectors.
 * 
 */
public class ManyConnectors
{
    public static void main(String[] args)
        throws Exception
    {
        Server server = new Server();
        
        SelectChannelConnector connector0 = new SelectChannelConnector();
        connector0.setPort(8080);
        connector0.setMaxIdleTime(5000);
        connector0.setName("connector 0");
       
        
        SelectChannelConnector connector1 = new SelectChannelConnector();
        connector1.setHost("127.0.0.1");
        connector1.setPort(8888);
        connector1.setName("connector 1");
        
        SocketConnector connector2 = new SocketConnector();
        connector2.setHost("127.0.0.2");
        connector2.setPort(8888);
        connector2.setThreadPool(new QueuedThreadPool());
        connector2.setName("connector 2");
        
        server.setConnectors(new Connector[]{connector0,connector1,connector2});
        
        server.setHandler(new HelloHandler());
        
        server.start();
        server.join();
    }

    public static class HelloHandler extends AbstractHandler
    {
        public void handle(String target, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println("<h1>Hello OneHandler</h1>");
            response.getWriter().println("from "+((Request)request).getConnection().getConnector().getName());
 
            ((Request)request).setHandled(true);
        }
    }
}
