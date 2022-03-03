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

package org.eclipse.jetty.ee9.websocket.server.config;

import java.util.Set;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.server.WebSocketMappings;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ServletContext configuration for Jetty Native WebSockets API.
 */
public class JettyWebSocketServletContainerInitializer implements ServletContainerInitializer
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyWebSocketServletContainerInitializer.class);
    private final Configurator configurator;

    public JettyWebSocketServletContainerInitializer()
    {
        this(null);
    }

    public JettyWebSocketServletContainerInitializer(Configurator configurator)
    {
        this.configurator = configurator;
    }

    public interface Configurator
    {
        void accept(ServletContext servletContext, JettyWebSocketServerContainer container);
    }

    /**
     * Configure the {@link ServletContextHandler} to call the {@link JettyWebSocketServletContainerInitializer}
     * during the {@link ServletContext} initialization phase.
     *
     * @param context the context to add listener to.
     * @param configurator a lambda that is called to allow the {@link WebSocketMappings} to
     * be configured during {@link ServletContext} initialization phase
     */
    public static void configure(ServletContextHandler context, Configurator configurator)
    {
        if (!context.isStopped())
            throw new IllegalStateException("configure should be called before starting");
        context.addServletContainerInitializer(new JettyWebSocketServletContainerInitializer(configurator));
    }

    /**
     * Immediately initialize the {@link ServletContextHandler} with the default {@link JettyWebSocketServerContainer}.
     *
     * <p>
     * This method is typically called from {@link #onStartup(Set, ServletContext)} itself or from
     * another dependent {@link ServletContainerInitializer} that requires minimal setup to
     * be performed.
     * </p>
     * <p>
     * This method SHOULD NOT BE CALLED by users of Jetty.
     * Use the {@link #configure(ServletContextHandler, Configurator)} method instead.
     * </p>
     * <p>
     * This will return the default {@link JettyWebSocketServerContainer} if already initialized,
     * and not create a new {@link JettyWebSocketServerContainer} each time it is called.
     * </p>
     *
     * @param context the context to work with
     * @return the default {@link JettyWebSocketServerContainer}
     */
    private static JettyWebSocketServerContainer initialize(ServletContextHandler context)
    {
        WebSocketComponents components = WebSocketServerComponents.ensureWebSocketComponents(context.getServer(), context.getServletContext());
        JettyWebSocketServerContainer container = JettyWebSocketServerContainer.ensureContainer(context.getServletContext());
        if (LOG.isDebugEnabled())
            LOG.debug("initialize {} {}", container, components);

        return container;
    }

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext context)
    {
        ServletContextHandler contextHandler = ServletContextHandler.getServletContextHandler(context, "Jetty WebSocket SCI");
        JettyWebSocketServerContainer container = JettyWebSocketServletContainerInitializer.initialize(contextHandler);
        if (LOG.isDebugEnabled())
            LOG.debug("onStartup {}", container);

        if (configurator != null)
        {
            configurator.accept(context, container);
        }
    }
}
