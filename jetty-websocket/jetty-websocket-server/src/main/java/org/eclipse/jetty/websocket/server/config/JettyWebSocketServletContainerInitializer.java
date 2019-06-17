//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server.config;

import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.websocket.servlet.WebSocketMapping;
import org.eclipse.jetty.websocket.servlet.WebSocketUpgradeFilter;

/**
 * ServletContext configuration for Jetty Native WebSockets API.
 */
public class JettyWebSocketServletContainerInitializer implements ServletContainerInitializer
{
    private static final Logger LOG = Log.getLogger(JettyWebSocketServletContainerInitializer.class);

    public static JettyWebSocketServerContainer configureContext(ServletContextHandler context)
    {
        WebSocketComponents components = WebSocketComponents.ensureWebSocketComponents(context.getServletContext());
        FilterHolder filterHolder = WebSocketUpgradeFilter.ensureFilter(context.getServletContext());
        WebSocketMapping mapping = WebSocketMapping.ensureMapping(context.getServletContext(), WebSocketMapping.DEFAULT_KEY);
        JettyWebSocketServerContainer container = JettyWebSocketServerContainer.ensureContainer(context.getServletContext());

        if (LOG.isDebugEnabled())
            LOG.debug("configureContext {} {} {} {}", container, mapping, filterHolder, components);

        return container;
    }

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext context) throws ServletException
    {
        ServletContextHandler contextHandler = ServletContextHandler.getServletContextHandler(context,"Jetty WebSocket SCI");
        JettyWebSocketServletContainerInitializer.configureContext(contextHandler);
    }
}
