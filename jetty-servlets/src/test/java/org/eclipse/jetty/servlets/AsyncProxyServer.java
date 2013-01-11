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

import java.util.EnumSet;

import javax.servlet.DispatcherType;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class AsyncProxyServer
{
    public static void main(String[] args)
        throws Exception
    {
        Server server = new Server();
        Connector connector=new SelectChannelConnector();
        connector.setPort(8888);
        server.setConnectors(new Connector[]{connector});
        
        ServletHandler handler=new ServletHandler();
        server.setHandler(handler);
        
        //FilterHolder gzip = handler.addFilterWithMapping("org.eclipse.jetty.servlet.GzipFilter","/*",EnumSet.of(DispatcherType.REQUEST,DispatcherType.ASYNC));
        //gzip.setAsyncSupported(true);
        //gzip.setInitParameter("minGzipSize","256");
        ServletHolder proxy = handler.addServletWithMapping("org.eclipse.jetty.servlets.ProxyServlet","/");
        proxy.setAsyncSupported(true);
        
        server.start();
        server.join();
    }
}
