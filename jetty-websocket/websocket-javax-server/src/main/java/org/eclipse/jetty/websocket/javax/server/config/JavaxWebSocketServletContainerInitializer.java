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

package org.eclipse.jetty.websocket.javax.server.config;

import java.util.HashSet;
import java.util.Set;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.HandlesTypes;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerApplicationConfig;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.ThreadClassLoaderScope;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.server.WebSocketMappings;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;
import org.eclipse.jetty.websocket.javax.server.internal.JavaxWebSocketServerContainer;
import org.eclipse.jetty.websocket.servlet.WebSocketUpgradeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@HandlesTypes({ServerApplicationConfig.class, ServerEndpoint.class, Endpoint.class})
public class JavaxWebSocketServletContainerInitializer implements ServletContainerInitializer
{
    /**
     * The ServletContext attribute key name for the
     * ServerContainer per javax.websocket spec 1.0 final section 6.4 Programmatic Server Deployment
     */
    public static final String ATTR_JAVAX_SERVER_CONTAINER = javax.websocket.server.ServerContainer.class.getName();

    public static final String ENABLE_KEY = "org.eclipse.jetty.websocket.javax";
    public static final String HTTPCLIENT_ATTRIBUTE = "org.eclipse.jetty.websocket.javax.HttpClient";
    private static final Logger LOG = LoggerFactory.getLogger(JavaxWebSocketServletContainerInitializer.class);

    private final Configurator configurator;

    public JavaxWebSocketServletContainerInitializer()
    {
        this(null);
    }

    public JavaxWebSocketServletContainerInitializer(Configurator configurator)
    {
        this.configurator = configurator;
    }

    /**
     * Test a ServletContext for {@code init-param} or {@code attribute} at {@code keyName} for
     * true or false setting that determines if the specified feature is enabled (or not).
     *
     * @param context the context to search
     * @param keyName the key name
     * @return the value for the feature key, otherwise null if key is not set in context
     */
    private static Boolean isEnabledViaContext(ServletContext context, String keyName)
    {
        // Try context parameters first
        String cp = context.getInitParameter(keyName);
        if (cp != null)
        {
            return TypeUtil.isTrue(cp);
        }

        // Next, try attribute on context
        Object enable = context.getAttribute(keyName);
        if (enable != null)
        {
            return TypeUtil.isTrue(enable);
        }

        return null;
    }

    public interface Configurator
    {
        void accept(ServletContext servletContext, ServerContainer serverContainer) throws DeploymentException;
    }

    /**
     * Configure the {@link ServletContextHandler} to call {@link JavaxWebSocketServletContainerInitializer#onStartup(Set, ServletContext)}
     * during the {@link ServletContext} initialization phase.
     *
     * @param context the context to add listener to
     * @param configurator the lambda that is called to allow the {@link ServerContainer} to
     * be configured during the {@link ServletContext} initialization phase
     */
    public static void configure(ServletContextHandler context, Configurator configurator)
    {
        if (!context.isStopped())
            throw new IllegalStateException("configure should be called before starting");
        context.addServletContainerInitializer(new JavaxWebSocketServletContainerInitializer(configurator));
    }

    /**
     * Immediately initialize the {@link ServletContext} with the default (and empty) {@link ServerContainer}.
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
     * There is no enablement check here, and no automatic deployment of endpoints at this point
     * in time.  It merely sets up the {@link ServletContext} so with the basics needed to start
     * configuring for `javax.websocket.server` based endpoints.
     * </p>
     *
     * @param context the context to work with
     * @return the default {@link ServerContainer} for this context
     */
    private static ServerContainer initialize(ServletContextHandler context)
    {
        JavaxWebSocketServerContainer serverContainer = JavaxWebSocketServerContainer.getContainer(context.getServletContext());
        if (serverContainer == null)
        {
            WebSocketComponents components = WebSocketServerComponents.ensureWebSocketComponents(context.getServer(), context.getServletContext());
            FilterHolder filterHolder = WebSocketUpgradeFilter.ensureFilter(context.getServletContext());
            WebSocketMappings mapping = WebSocketMappings.ensureMappings(context.getServletContext());
            serverContainer = JavaxWebSocketServerContainer.ensureContainer(context.getServletContext());

            if (LOG.isDebugEnabled())
                LOG.debug("configureContext {} {} {} {}", mapping, components, filterHolder, serverContainer);
        }
        return serverContainer;
    }

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext context) throws ServletException
    {
        Boolean enableKey = isEnabledViaContext(context, ENABLE_KEY);

        boolean websocketEnabled = true;
        if (enableKey != null)
            websocketEnabled = enableKey;

        if (!websocketEnabled)
        {
            LOG.info("Javax Websocket is disabled by configuration for context {}", context.getContextPath());
            return;
        }

        ServletContextHandler servletContextHandler = ServletContextHandler.getServletContextHandler(context, "Javax WebSocket SCI");
        ServerContainer container = initialize(servletContextHandler);

        try (ThreadClassLoaderScope scope = new ThreadClassLoaderScope(context.getClassLoader()))
        {
            // Create the Jetty ServerContainer implementation
            if (LOG.isDebugEnabled())
                LOG.debug("Found {} classes", c.size());

            // Now process the incoming classes
            Set<Class<? extends Endpoint>> discoveredExtendedEndpoints = new HashSet<>();
            Set<Class<?>> discoveredAnnotatedEndpoints = new HashSet<>();
            Set<Class<? extends ServerApplicationConfig>> serverAppConfigs = new HashSet<>();

            filterClasses(c, discoveredExtendedEndpoints, discoveredAnnotatedEndpoints, serverAppConfigs);

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Discovered {} extends Endpoint classes", discoveredExtendedEndpoints.size());
                LOG.debug("Discovered {} @ServerEndpoint classes", discoveredAnnotatedEndpoints.size());
                LOG.debug("Discovered {} ServerApplicationConfig classes", serverAppConfigs.size());
            }

            // Process the server app configs to determine endpoint filtering
            boolean wasFiltered = false;
            Set<ServerEndpointConfig> deployableExtendedEndpointConfigs = new HashSet<>();
            Set<Class<?>> deployableAnnotatedEndpoints = new HashSet<>();

            for (Class<? extends ServerApplicationConfig> clazz : serverAppConfigs)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Found ServerApplicationConfig: {}", clazz);

                try
                {
                    ServerApplicationConfig config = clazz.getDeclaredConstructor().newInstance();

                    Set<ServerEndpointConfig> seconfigs = config.getEndpointConfigs(discoveredExtendedEndpoints);
                    if (seconfigs != null)
                    {
                        wasFiltered = true;
                        deployableExtendedEndpointConfigs.addAll(seconfigs);
                    }

                    Set<Class<?>> annotatedClasses = config.getAnnotatedEndpointClasses(discoveredAnnotatedEndpoints);
                    if (annotatedClasses != null)
                    {
                        wasFiltered = true;
                        deployableAnnotatedEndpoints.addAll(annotatedClasses);
                    }
                }
                catch (Exception e)
                {
                    throw new ServletException("Unable to instantiate: " + clazz.getName(), e);
                }
            }

            // Default behavior if nothing filtered
            if (!wasFiltered)
            {
                deployableAnnotatedEndpoints.addAll(discoveredAnnotatedEndpoints);
                // Note: it is impossible to determine path of "extends Endpoint" discovered classes
                deployableExtendedEndpointConfigs = new HashSet<>();
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Deploying {} ServerEndpointConfig(s)", deployableExtendedEndpointConfigs.size());
            }
            // Deploy what should be deployed.
            for (ServerEndpointConfig config : deployableExtendedEndpointConfigs)
            {
                try
                {
                    container.addEndpoint(config);
                }
                catch (DeploymentException e)
                {
                    throw new ServletException(e);
                }
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Deploying {} @ServerEndpoint(s)", deployableAnnotatedEndpoints.size());
            }
            for (Class<?> annotatedClass : deployableAnnotatedEndpoints)
            {
                try
                {
                    container.addEndpoint(annotatedClass);
                }
                catch (DeploymentException e)
                {
                    throw new ServletException(e);
                }
            }
        }

        // Call the configurator after startup.
        if (configurator != null)
        {
            try
            {
                configurator.accept(context, container);
            }
            catch (DeploymentException e)
            {
                throw new RuntimeException("Failed to deploy WebSocket Endpoint", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void filterClasses(Set<Class<?>> c, Set<Class<? extends Endpoint>> discoveredExtendedEndpoints, Set<Class<?>> discoveredAnnotatedEndpoints,
                               Set<Class<? extends ServerApplicationConfig>> serverAppConfigs)
    {
        for (Class<?> clazz : c)
        {
            if (ServerApplicationConfig.class.isAssignableFrom(clazz))
            {
                serverAppConfigs.add((Class<? extends ServerApplicationConfig>)clazz);
            }

            if (Endpoint.class.isAssignableFrom(clazz))
            {
                discoveredExtendedEndpoints.add((Class<? extends Endpoint>)clazz);
            }

            ServerEndpoint endpoint = clazz.getAnnotation(ServerEndpoint.class);

            if (endpoint != null)
            {
                discoveredAnnotatedEndpoints.add(clazz);
            }
        }
    }
}
