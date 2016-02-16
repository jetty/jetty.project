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

package org.eclipse.jetty.cdi.websocket;

import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.ThreadClassLoaderScope;

public class WebSocketCdiInitializer implements ServletContainerInitializer
{
    public static void configureContext(ServletContextHandler context) throws ServletException
    {
        try (ThreadClassLoaderScope scope = new ThreadClassLoaderScope(context.getClassLoader()))
        {
            addListeners(context);
        }
    }

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext context) throws ServletException
    {
        ContextHandler handler = ContextHandler.getContextHandler(context);

        if (handler == null)
        {
            throw new ServletException("Not running on Jetty, WebSocket+CDI support unavailable");
        }

        if (!(handler instanceof ServletContextHandler))
        {
            throw new ServletException("Not running in Jetty ServletContextHandler, WebSocket+CDI support unavailable");
        }

        ServletContextHandler jettyContext = (ServletContextHandler)handler;
        try (ThreadClassLoaderScope scope = new ThreadClassLoaderScope(context.getClassLoader()))
        {
            addListeners(jettyContext);
        }
    }
    
    private static void addListeners(ContainerLifeCycle container)
    {
        WebSocketCdiListener listener = new WebSocketCdiListener();
        container.addLifeCycleListener(listener);
        container.addEventListener(listener);
    }
}
