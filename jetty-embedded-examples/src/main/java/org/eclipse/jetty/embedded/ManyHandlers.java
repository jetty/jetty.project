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
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;

public class ManyHandlers
{
    public static void main(String[] args)
        throws Exception
    {
        Server server = new Server();
        Connector connector=new SelectChannelConnector();
        connector.setPort(8080);
        server.setConnectors(new Connector[]{connector});
        
        Handler param=new ParamHandler();
        Handler hello=new HelloHandler();
        
        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[]{param,hello});
        server.setHandler(handlers);
        
        server.start();
        server.join();
    }

    public static class ParamHandler extends AbstractHandler
    {
        public void handle(String target, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            System.err.println(request.getParameterMap());
        }
    }
    
    public static class HelloHandler extends AbstractHandler
    {
        public void handle(String target, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            Request base_request = (request instanceof Request) ? (Request)request:HttpConnection.getCurrentConnection().getRequest();
            base_request.setHandled(true);
            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println("<h1>Hello ManyHandler</h1>");
        }
    }
}
