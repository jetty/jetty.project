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

package org.eclipse.jetty.continuation;

import org.eclipse.jetty.servlets.ProxyServlet;
import org.junit.Ignore;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.FilterHolder;
import org.mortbay.jetty.servlet.ServletHandler;
import org.mortbay.jetty.servlet.ServletHolder;

@Ignore("Not a test case")
public class TestProxyServer
{
    public static void main(String[] args) throws Exception
    {
        Server server = new Server();
        SelectChannelConnector selectChannelConnector = new SelectChannelConnector();
        server.setConnectors(new Connector[]{ selectChannelConnector });
        selectChannelConnector.setPort(8080);
            
        Context servletContext = new Context(Context.NO_SECURITY|Context.NO_SESSIONS);
        server.setHandler(servletContext);
        ServletHandler servletHandler=servletContext.getServletHandler();
        
        
        ServletHolder proxy=new ServletHolder(ProxyServlet.Transparent.class);
        servletHandler.addServletWithMapping(proxy,"/ws/*");
        proxy.setInitParameter("ProxyTo","http://www.webtide.com");
        proxy.setInitParameter("Prefix","/ws");
        
        FilterHolder filter=servletHandler.addFilterWithMapping(ContinuationFilter.class,"/*",0);
        filter.setInitParameter("debug","true");
        
        server.start();
        server.join();
    }
}
