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
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;

public class ManyContexts
{
    public static void main(String[] args)
        throws Exception
    {
        Server server = new Server();
        Connector connector=new SelectChannelConnector();
        connector.setPort(8080);
        server.setConnectors(new Connector[]{connector});
        
        ContextHandler context0 = new ContextHandler();
        context0.setContextPath("/zero");
        Handler handler0=new HelloHandler();
        context0.setHandler(handler0);

        ContextHandler context1 = new ContextHandler();
        context1.setContextPath("/one");
        Handler handler1=new HelloHandler();
        context1.setHandler(handler1);   
        
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.setHandlers(new Handler[]{context0,context1});
        
        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[]{contexts,new DefaultHandler()});
        
        server.setHandler(handlers);
        
        server.start();
        server.join();
    }

    public static class HelloHandler extends AbstractHandler
    {
        static int h=0;
        int hello=h++;

        public void handle(String target, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            Request base_request = (request instanceof Request) ? (Request)request:HttpConnection.getCurrentConnection().getRequest();
            base_request.setHandled(true);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/html");
            response.getWriter().println("<h1>Hello OneContext "+hello+"</h1>");
        }
    }
    
}
