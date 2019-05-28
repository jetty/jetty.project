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

package org.eclipse.jetty.websocket.server;

import java.util.Set;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;

import org.eclipse.jetty.server.handler.ContextHandler;
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
     * Initialize the ServletContext with the default (and empty) {@link NativeWebSocketConfiguration}
     *
     * @param context the context to work with
     */
    public static void initialize(ServletContext context)
    {
        NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration)context.getAttribute(ATTR_KEY);
        if (configuration != null)
            return; // it exists.

        // Not provided to us, create a new default one.
        configuration = new NativeWebSocketConfiguration(context);
        context.setAttribute(ATTR_KEY, configuration);

        // Attach default configuration to context lifecycle
        if (context instanceof ContextHandler.Context)
        {
            ContextHandler handler = ((ContextHandler.Context)context).getContextHandler();
            // Let ContextHandler handle configuration lifecycle
            handler.addManaged(configuration);
        }
    }

    /**
     * Configure the {@link ServletContextHandler} to call the {@link NativeWebSocketServletContainerInitializer}
     * during the {@link ServletContext} initialization phase.
     *
     * @param context the context to add listener to.
     */
    public static void configure(ServletContextHandler context)
    {
        context.addEventListener(ContainerInitializer.asContextListener(new NativeWebSocketServletContainerInitializer()));
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
                .setPostOnStartupConsumer((servletContext) ->
                {
                    NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration)servletContext.getAttribute(ATTR_KEY);
                    configurator.accept(servletContext, configuration);
                }));
    }

    /**
     * Obtain the default {@link NativeWebSocketConfiguration} from the {@link ServletContext}
     *
     * @param context the context to work with
     * @return the default {@link NativeWebSocketConfiguration}
     * @see #initialize(ServletContext)
     * @see #configure(ServletContextHandler)
     * @see #configure(ServletContextHandler, Configurator)
     * @deprecated use {@link #configure(ServletContextHandler, Configurator)} instead
     */
    @Deprecated
    public static NativeWebSocketConfiguration getDefaultFrom(ServletContext context)
    {
        initialize(context);
        return (NativeWebSocketConfiguration)context.getAttribute(ATTR_KEY);
    }

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx)
    {
        // initialize
        initialize(ctx);
    }
}
