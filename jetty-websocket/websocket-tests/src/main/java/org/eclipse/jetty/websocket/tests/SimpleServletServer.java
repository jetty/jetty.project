//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests;

import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.servlet.impl.WebSocketUpgradeFilter;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

public class SimpleServletServer extends LocalServer implements LocalFuzzer.Provider
{
    private final HttpServlet servlet;

    public SimpleServletServer(HttpServlet servlet)
    {
        super();
        this.servlet = servlet;
    }

    protected void configureServletContextHandler(ServletContextHandler context)
    {
        // Serve capture servlet
        context.addServlet(new ServletHolder(servlet),"/*");
    }
    
    public WebSocketServletFactory getWebSocketServletFactory()
    {
        // Try filter approach first
        WebSocketUpgradeFilter filter = (WebSocketUpgradeFilter) this.servlet.getServletContext().getAttribute(WebSocketUpgradeFilter.class.getName());
        if (filter != null)
        {
            return filter.getFactory();
        }
        
        // Try servlet next
        return (WebSocketServletFactory) this.servlet.getServletContext().getAttribute(WebSocketServletFactory.class.getName());
    }
}
