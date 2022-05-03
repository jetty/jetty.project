//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.jakarta.client;

import java.util.Set;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This manages the LifeCycle of {@link jakarta.websocket.WebSocketContainer} instances that are created with
 * {@link jakarta.websocket.ContainerProvider}, if this code is being run from another ServletContainer, or if run inside a
 * Jetty Server with the WebSocket client classes provided by the webapp.</p>
 *
 * <p>This mechanism will not work if run with embedded Jetty or if the WebSocket client classes are provided by the server.
 * In this case then the client {@link jakarta.websocket.WebSocketContainer} will register itself to be automatically shutdown
 * with the Jetty {@code ContextHandler}.</p>
 */
public class JakartaWebSocketShutdownContainer extends ContainerLifeCycle implements ServletContainerInitializer, ServletContextListener
{
    private static final Logger LOG = LoggerFactory.getLogger(JakartaWebSocketShutdownContainer.class);

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
    {
        JakartaWebSocketClientContainer.setShutdownContainer(this);
        ctx.addListener(this);
    }

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("contextInitialized({}) {}", sce, this);
        LifeCycle.start(this);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("contextDestroyed({}) {}", sce, this);

        LifeCycle.stop(this);
        removeBeans();
        JakartaWebSocketClientContainer.setShutdownContainer(null);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s, size=%s}", getClass().getSimpleName(), hashCode(), getState(), getBeans().size());
    }
}
