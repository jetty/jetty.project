//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFrameHandlerFactory;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * ServletContext configuration for Jetty Native WebSockets API.
 */
public class JettyWebSocketServletContainerInitializer implements ServletContainerInitializer
{
    public static class JettyWebSocketEmbeddedStarter extends AbstractLifeCycle implements ServletContextHandler.ServletContainerInitializerCaller
    {
        private ServletContainerInitializer sci;
        private ServletContextHandler context;

        public JettyWebSocketEmbeddedStarter(ServletContainerInitializer sci, ServletContextHandler context)
        {
            this.sci = sci;
            this.context = context;
        }

        public void doStart()
        {
            try
            {
                Set<Class<?>> c = Collections.emptySet();
                sci.onStartup(c, context.getServletContext());
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    public static void configure(ServletContextHandler contextHandler)
    {
        JettyWebSocketServletContainerInitializer sci = new JettyWebSocketServletContainerInitializer();
        JettyWebSocketEmbeddedStarter starter = new JettyWebSocketEmbeddedStarter(sci, contextHandler);
        contextHandler.addBean(starter);
    }

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
    {
        // TODO why doesn't the javax side share this approach?
        List<WebSocketServletFrameHandlerFactory> factories = (List<WebSocketServletFrameHandlerFactory>) ctx.getAttribute(WebSocketServletFrameHandlerFactory.ATTR_HANDLERS);
        if(factories == null)
        {
            factories = new ArrayList<>();
            ctx.setAttribute(WebSocketServletFrameHandlerFactory.ATTR_HANDLERS,factories);
        }

        Executor executor = (Executor) ctx.getAttribute("org.eclipse.jetty.server.Executor");
        if(executor == null)
        {
            try
            {
                QueuedThreadPool threadPool = new QueuedThreadPool();
                threadPool.setName("Jetty-WebSocketServer");
                threadPool.start();
                executor = threadPool;
            }
            catch (Exception e)
            {
                throw new ServletException("Unable to start internal Executor", e);
            }
        }

        factories.add(new JettyWebSocketServletFrameHandlerFactory(executor));
    }
}
