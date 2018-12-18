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

package org.eclipse.jetty.websocket.javax.server;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
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
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ThreadClassLoaderScope;
import org.eclipse.jetty.websocket.servlet.WebSocketCreatorMapping;
import org.eclipse.jetty.websocket.servlet.WebSocketUpgradeFilter;

@HandlesTypes({ ServerApplicationConfig.class, ServerEndpoint.class, Endpoint.class })
public class JavaxWebSocketServerContainerInitializer implements ServletContainerInitializer
{
    public static final String ENABLE_KEY = "org.eclipse.jetty.websocket.javax";
    public static final String DEPRECATED_ENABLE_KEY = "org.eclipse.jetty.websocket.jsr356";
    private static final Logger LOG = Log.getLogger(JavaxWebSocketServerContainerInitializer.class);
    public static final String HTTPCLIENT_ATTRIBUTE = "org.eclipse.jetty.websocket.javax.HttpClient";

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
            //remove any ServerContainer beans
            if (sce.getServletContext() instanceof ContextHandler.Context)
            {
                ContextHandler handler = ((ContextHandler.Context)sce.getServletContext()).getContextHandler();
                JavaxWebSocketServerContainer bean = handler.getBean(JavaxWebSocketServerContainer.class);
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
     * @param context  the context to search
     * @param keyName  the key name
     * @param defValue the default value, if the value is not specified in the context
     * @return the value for the feature key
     */
    public static Boolean isEnabledViaContext(ServletContext context, String keyName, Boolean defValue)
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

    /**
     * Jetty Native approach.
     * <p>
     * Note: this will add the Upgrade filter to the existing list, with no regard for order.  It will just be tacked onto the end of the list.
     *
     * @param context the servlet context handler
     * @return the created websocket server container
     * @throws ServletException if unable to create the websocket server container
     */
    public static JavaxWebSocketServerContainer configureContext(ServletContextHandler context) throws ServletException
    {
        WebSocketUpgradeFilter.configureContext(context);
        WebSocketCreatorMapping webSocketCreatorMapping = (WebSocketCreatorMapping)context.getAttribute(WebSocketCreatorMapping.class.getName());

        // Find Pre-Existing (Shared?) HttpClient and/or executor
        HttpClient httpClient = (HttpClient)context.getServletContext().getAttribute(HTTPCLIENT_ATTRIBUTE);
        if (httpClient == null)
            httpClient = (HttpClient)context.getServer().getAttribute(HTTPCLIENT_ATTRIBUTE);

        Executor executor = httpClient == null?null:httpClient.getExecutor();
        if (executor == null)
            executor = (Executor)context.getAttribute("org.eclipse.jetty.server.Executor");
        if (executor == null)
            executor = context.getServer().getThreadPool();

        if (httpClient!=null && httpClient.getExecutor()==null)
            httpClient.setExecutor(executor);

        // Create the Jetty ServerContainer implementation
        JavaxWebSocketServerContainer jettyContainer = new JavaxWebSocketServerContainer(webSocketCreatorMapping, httpClient, executor);
        context.addBean(jettyContainer);

        // Add WebSocketServletFrameHandlerFactory to servlet container for this JSR container
        webSocketCreatorMapping.addFrameHandlerFactory(jettyContainer.getFrameHandlerFactory());

        // Store a reference to the ServerContainer per - javax.websocket spec 1.0 final - section 6.4: Programmatic Server Deployment
        context.setAttribute(javax.websocket.server.ServerContainer.class.getName(), jettyContainer);

        return jettyContainer;
    }

    /**
     * @param context      not used
     * @param jettyContext the {@link ServletContextHandler} to use
     * @return a configured {@link JavaxWebSocketServerContainer} instance
     * @throws ServletException if the {@link WebSocketUpgradeFilter} cannot be configured
     * @deprecated use {@link #configureContext(ServletContextHandler)} instead
     */
    @Deprecated
    public static JavaxWebSocketServerContainer configureContext(ServletContext context, ServletContextHandler jettyContext) throws ServletException
    {
        return configureContext(jettyContext);
    }

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext context) throws ServletException
    {
        Boolean dft = isEnabledViaContext(context, DEPRECATED_ENABLE_KEY, null);
        if (dft==null)
            dft = Boolean.TRUE;
        else
            LOG.warn("Deprecated parameter used: " + DEPRECATED_ENABLE_KEY);

        if (!isEnabledViaContext(context, ENABLE_KEY, dft))
        {
            LOG.info("Javax Websocket is disabled by configuration for context {}", context.getContextPath());
            return;
        }

        ContextHandler handler = ContextHandler.getContextHandler(context);

        if (handler == null)
        {
            throw new ServletException("Not running on Jetty, Javax Websocket support unavailable");
        }

        if (!(handler instanceof ServletContextHandler))
        {
            throw new ServletException("Not running in Jetty ServletContextHandler, Javax Websocket support unavailable");
        }

        ServletContextHandler jettyContext = (ServletContextHandler)handler;

        try (ThreadClassLoaderScope scope = new ThreadClassLoaderScope(context.getClassLoader()))
        {
            // Create the Jetty ServerContainer implementation
            JavaxWebSocketServerContainer jettyContainer = configureContext(jettyContext);
            context.addListener(new ContextDestroyListener()); // make sure context is cleaned up when the context stops

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
