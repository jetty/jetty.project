//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Set;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.listener.ContainerInitializer;

public class NativeWebSocketServletContainerInitializer implements ServletContainerInitializer
{
    public static final String ATTR_KEY = NativeWebSocketConfiguration.class.getName();

    public interface Configurator
    {
        void accept(ServletContext servletContext, NativeWebSocketConfiguration nativeWebSocketConfiguration);
    }

    /**
     * Immediately initialize the {@link ServletContextHandler} with the default {@link NativeWebSocketConfiguration}.
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
     * This will return the default {@link NativeWebSocketConfiguration} if already initialized,
     * and not create a new {@link NativeWebSocketConfiguration} each time it is called.
     * </p>
     *
     * @param context the context to work with
     * @return the default {@link NativeWebSocketConfiguration}
     */
    public static NativeWebSocketConfiguration initialize(ServletContextHandler context)
    {
        NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration)context.getAttribute(ATTR_KEY);
        if (configuration == null)
        {
            // Not provided to us, create a new default one.
            configuration = new NativeWebSocketConfiguration(context.getServletContext());
            context.setAttribute(ATTR_KEY, configuration);

            // Attach default configuration to context lifecycle
            context.addManaged(configuration);
        }
        return configuration;
    }

    /**
     * Configure the {@link ServletContextHandler} to call the {@link NativeWebSocketServletContainerInitializer}
     * during the {@link ServletContext} initialization phase.
     *
     * @param context the context to add listener to.
     * @param configurator a lambda that is called to allow the {@link NativeWebSocketConfiguration} to
     * be configured during {@link ServletContext} initialization phase
     */
    public static void configure(ServletContextHandler context, Configurator configurator)
    {
        context.addEventListener(
            ContainerInitializer
                .asContextListener(new NativeWebSocketServletContainerInitializer())
                .afterStartup((servletContext) ->
                {
                    if (configurator != null)
                    {
                        NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration)servletContext.getAttribute(ATTR_KEY);
                        configurator.accept(servletContext, configuration);
                    }
                }));
    }

    /**
     * Obtain the default {@link NativeWebSocketConfiguration} from the {@link ServletContext}
     *
     * @param context the context to work with
     * @return the default {@link NativeWebSocketConfiguration}
     * @see #configure(ServletContextHandler, Configurator)
     * @deprecated use {@link #configure(ServletContextHandler, Configurator)} instead
     */
    @Deprecated
    public static NativeWebSocketConfiguration getDefaultFrom(ServletContext context)
    {
        ServletContextHandler handler = ServletContextHandler.getServletContextHandler(context);
        if (handler == null)
        {
            throw new IllegalStateException("Unable to find ServletContextHandler for provided ServletContext");
        }
        return initialize(handler);
    }

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext context)
    {
        ServletContextHandler handler = ServletContextHandler.getServletContextHandler(context);
        if (handler == null)
        {
            throw new IllegalStateException("Unable to find ServletContextHandler for provided ServletContext");
        }
        initialize(handler);
    }
}
