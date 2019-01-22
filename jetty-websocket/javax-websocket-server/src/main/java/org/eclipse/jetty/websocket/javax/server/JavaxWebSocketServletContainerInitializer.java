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

package org.eclipse.jetty.websocket.javax.server;

import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.HandlesTypes;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerApplicationConfig;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ThreadClassLoaderScope;
import org.eclipse.jetty.websocket.core.WebSocketResources;
import org.eclipse.jetty.websocket.servlet.WebSocketMapping;
import org.eclipse.jetty.websocket.servlet.WebSocketUpgradeFilter;

@HandlesTypes({ ServerApplicationConfig.class, ServerEndpoint.class, Endpoint.class })
public class JavaxWebSocketServletContainerInitializer implements ServletContainerInitializer
{
    public static final String ENABLE_KEY = "org.eclipse.jetty.websocket.javax";
    public static final String DEPRECATED_ENABLE_KEY = "org.eclipse.jetty.websocket.jsr356";
    public static final String HTTPCLIENT_ATTRIBUTE = "org.eclipse.jetty.websocket.javax.HttpClient";
    private static final Logger LOG = Log.getLogger(JavaxWebSocketServletContainerInitializer.class);

    /**
     * Test a ServletContext for {@code init-param} or {@code attribute} at {@code keyName} for
     * true or false setting that determines if the specified feature is enabled (or not).
     *
     * @param context  the context to search
     * @param keyName  the key name
     * @return the value for the feature key, otherwise null if key is not set in context
     */
    private static Boolean isEnabledViaContext(ServletContext context, String keyName)
    {
        // Try context parameters first
        String cp = context.getInitParameter(keyName);
        if (cp != null)
        {
            if (TypeUtil.isTrue(cp))
                return true;
            else
                return false;
        }

        // Next, try attribute on context
        Object enable = context.getAttribute(keyName);
        if (enable != null)
        {
            if (TypeUtil.isTrue(enable))
                return true;
            else
                return false;
        }

        return null;
    }

    public static JavaxWebSocketServerContainer configureContext(ServletContextHandler context) throws ServletException
    {
        WebSocketResources resources = WebSocketResources.ensureWebSocketResources(context.getServletContext());
        FilterHolder filterHolder = WebSocketUpgradeFilter.ensureFilter(context.getServletContext());

        WebSocketUpgradeFilter upgradeFilter = ((WebSocketUpgradeFilter)filterHolder.getFilter());
        if (upgradeFilter.getMapping() == null)
            upgradeFilter.setMapping(new WebSocketMapping(resources));

        JavaxWebSocketServerContainer container = JavaxWebSocketServerContainer.ensureContainer(context.getServletContext());

        if (LOG.isDebugEnabled())
            LOG.debug("configureContext {} {} {} {}", upgradeFilter.getMapping(), resources, upgradeFilter, container);

        return container;
    }

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext context) throws ServletException
    {
        Boolean enableKey = isEnabledViaContext(context, ENABLE_KEY);
        Boolean deprecatedEnabledKey = isEnabledViaContext(context, DEPRECATED_ENABLE_KEY);
        if (deprecatedEnabledKey != null)
            LOG.warn("Deprecated parameter used: " + DEPRECATED_ENABLE_KEY);

        boolean websocketEnabled = true;
        if (enableKey != null)
            websocketEnabled = enableKey;
        else if (deprecatedEnabledKey != null)
            websocketEnabled = deprecatedEnabledKey;

        if (!websocketEnabled)
        {
            LOG.info("Javax Websocket is disabled by configuration for context {}", context.getContextPath());
            return;
        }

        JavaxWebSocketServerContainer container = configureContext(ServletContextHandler.getServletContextHandler(context,"Javax WebSocket SCI"));

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
