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

package org.eclipse.jetty.websocket.jsr356.server.deploy;

import java.util.HashSet;
import java.util.Set;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.annotation.HandlesTypes;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerApplicationConfig;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.listener.ContainerInitializer;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ThreadClassLoaderScope;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.server.NativeWebSocketConfiguration;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;

@HandlesTypes(
    {ServerApplicationConfig.class, ServerEndpoint.class, Endpoint.class})
public class WebSocketServerContainerInitializer implements ServletContainerInitializer
{
    /**
     * The ServletContext attribute key name for the
     * ServerContainer per javax.websocket spec 1.0 final section 6.4 Programmatic Server Deployment
     */
    public static final String ATTR_JAVAX_SERVER_CONTAINER = javax.websocket.server.ServerContainer.class.getName();

    public static final String ENABLE_KEY = "org.eclipse.jetty.websocket.jsr356";
    public static final String ADD_DYNAMIC_FILTER_KEY = "org.eclipse.jetty.websocket.jsr356.addDynamicFilter";
    private static final Logger LOG = Log.getLogger(WebSocketServerContainerInitializer.class);
    public static final String HTTPCLIENT_ATTRIBUTE = "org.eclipse.jetty.websocket.jsr356.HttpClient";

    /**
     * DestroyListener
     */
    public static class ContextDestroyListener implements ServletContextListener
    {
        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            //noop
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
            // remove any ServerContainer beans
            ServletContextHandler handler = ServletContextHandler.getServletContextHandler(sce.getServletContext());
            if (handler != null)
            {
                ServerContainer bean = handler.getBean(ServerContainer.class);
                if (bean != null)
                    handler.removeBean(bean);
            }

            //remove reference in attributes
            sce.getServletContext().removeAttribute(javax.websocket.server.ServerContainer.class.getName());
        }
    }

    /**
     * Test a ServletContext for {@code init-param} or {@code attribute} at {@code keyName} for
     * true or false setting that determines if the specified feature is enabled (or not).
     *
     * @param context the context to search
     * @param keyName the key name
     * @param defValue the default value, if the value is not specified in the context
     * @return the value for the feature key
     */
    public static boolean isEnabledViaContext(ServletContext context, String keyName, boolean defValue)
    {
        // Try context parameters first
        String cp = context.getInitParameter(keyName);

        if (cp != null)
        {
            if (TypeUtil.isTrue(cp))
            {
                return true;
            }

            if (TypeUtil.isFalse(cp))
            {
                return false;
            }

            return defValue;
        }

        // Next, try attribute on context
        Object enable = context.getAttribute(keyName);

        if (enable != null)
        {
            if (TypeUtil.isTrue(enable))
            {
                return true;
            }

            if (TypeUtil.isFalse(enable))
            {
                return false;
            }
        }

        return defValue;
    }

    public interface Configurator
    {
        void accept(ServletContext servletContext, ServerContainer serverContainer) throws DeploymentException;
    }

    /**
     * @param context the {@link ServletContextHandler} to use
     * @return a configured {@link ServerContainer} instance
     * @throws ServletException if the {@link WebSocketUpgradeFilter} cannot be configured
     * @deprecated use {@link #configure(ServletContextHandler, Configurator)} instead
     */
    @Deprecated
    public static ServerContainer configureContext(ServletContextHandler context) throws ServletException
    {
        return initialize(context);
    }

    /**
     * @param context not used
     * @param jettyContext the {@link ServletContextHandler} to use
     * @return a configured {@link ServerContainer} instance
     * @throws ServletException if the {@link WebSocketUpgradeFilter} cannot be configured
     * @deprecated use {@link #configure(ServletContextHandler, Configurator)} instead
     */
    @Deprecated
    public static ServerContainer configureContext(ServletContext context, ServletContextHandler jettyContext) throws ServletException
    {
        return initialize(jettyContext);
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
    public static ServerContainer initialize(ServletContextHandler context) throws ServletException
    {
        ServerContainer serverContainer = (ServerContainer)context.getAttribute(ATTR_JAVAX_SERVER_CONTAINER);
        if (serverContainer == null)
        {
            // Create Basic components
            NativeWebSocketConfiguration nativeWebSocketConfiguration = NativeWebSocketServletContainerInitializer.initialize(context);

            // Obtain HttpClient
            HttpClient httpClient = (HttpClient)context.getAttribute(HTTPCLIENT_ATTRIBUTE);
            if (httpClient == null)
            {
                Server server = context.getServer();
                if (server != null)
                {
                    httpClient = (HttpClient)server.getAttribute(HTTPCLIENT_ATTRIBUTE);
                }
            }

            // Create the Jetty ServerContainer implementation
            serverContainer = new ServerContainer(nativeWebSocketConfiguration, httpClient);
            context.addBean(serverContainer);

            // Store a reference to the ServerContainer per javax.websocket spec 1.0 final section 6.4 Programmatic Server Deployment
            context.setAttribute(ATTR_JAVAX_SERVER_CONTAINER, serverContainer);

            // Create Filter
            if (isEnabledViaContext(context.getServletContext(), ADD_DYNAMIC_FILTER_KEY, true))
            {
                WebSocketUpgradeFilter.configure(context);
            }
        }
        return serverContainer;
    }

    /**
     * Configure the {@link ServletContextHandler} to call {@link WebSocketServerContainerInitializer#onStartup(Set, ServletContext)}
     * during the {@link ServletContext} initialization phase.
     *
     * @param context the context to add listener to
     * @param configurator the lambda that is called to allow the {@link ServerContainer} to
     * be configured during the {@link ServletContext} initialization phase
     */
    public static void configure(ServletContextHandler context, Configurator configurator)
    {
        // In this embedded-jetty usage, allow ServletContext.addListener() to
        // add other ServletContextListeners (such as the ContextDestroyListener) after
        // the initialization phase is over. (important for this SCI to function)
        context.getServletContext().setExtendedListenerTypes(true);

        context.addEventListener(ContainerInitializer.asContextListener(new WebSocketServerContainerInitializer())
            .afterStartup((servletContext) ->
            {
                ServerContainer serverContainer = (ServerContainer)servletContext.getAttribute(ATTR_JAVAX_SERVER_CONTAINER);
                if (configurator != null)
                {
                    try
                    {
                        configurator.accept(servletContext, serverContainer);
                    }
                    catch (DeploymentException e)
                    {
                        throw new RuntimeException("Failed to deploy WebSocket Endpoint", e);
                    }
                }
            }));
    }

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext context) throws ServletException
    {
        if (!isEnabledViaContext(context, ENABLE_KEY, true))
        {
            LOG.info("JSR-356 is disabled by configuration for context {}", context.getContextPath());
            return;
        }

        ServletContextHandler handler = ServletContextHandler.getServletContextHandler(context);

        if (handler == null)
        {
            throw new ServletException("Not running on Jetty, JSR-356 support unavailable");
        }

        try (ThreadClassLoaderScope scope = new ThreadClassLoaderScope(context.getClassLoader()))
        {
            // Initialize the Jetty ServerContainer implementation
            ServerContainer jettyContainer = initialize(handler);
            context.addListener(new ContextDestroyListener()); // make sure context is cleaned up when the context stops

            if (c.isEmpty())
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("No JSR-356 annotations or interfaces discovered");
                }
                return;
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Found {} classes", c.size());
            }

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
                {
                    LOG.debug("Found ServerApplicationConfig: {}", clazz);
                }
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
                    jettyContainer.addEndpoint(config);
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
                    jettyContainer.addEndpoint(annotatedClass);
                }
                catch (DeploymentException e)
                {
                    throw new ServletException(e);
                }
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
