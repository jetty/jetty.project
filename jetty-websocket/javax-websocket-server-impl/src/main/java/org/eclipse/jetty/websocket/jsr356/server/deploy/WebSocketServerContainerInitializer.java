//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.server.NativeWebSocketConfiguration;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;

@HandlesTypes(
{ ServerApplicationConfig.class, ServerEndpoint.class, Endpoint.class })
public class WebSocketServerContainerInitializer implements ServletContainerInitializer
{
    public static final String ENABLE_KEY = "org.eclipse.jetty.websocket.jsr356";
    public static final String ADD_DYNAMIC_FILTER_KEY = "org.eclipse.jetty.websocket.jsr356.addDynamicFilter";
    private static final Logger LOG = Log.getLogger(WebSocketServerContainerInitializer.class);
    
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
    
        if(cp != null)
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
        Object enable = context.getAttribute(ENABLE_KEY);
    
        if(enable != null)
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
     * Embedded Jetty approach for non-bytecode scanning.
     */
    public static ServerContainer configureContext(ServletContextHandler context) throws ServletException
    {
        // Create Basic components
        NativeWebSocketConfiguration nativeWebSocketConfiguration = NativeWebSocketServletContainerInitializer.getDefaultFrom(context.getServletContext());
        
        // Create the Jetty ServerContainer implementation
        ServerContainer jettyContainer = new ServerContainer(nativeWebSocketConfiguration, context.getServer().getThreadPool());
        context.addBean(jettyContainer);
        
        // Store a reference to the ServerContainer per javax.websocket spec 1.0 final section 6.4 Programmatic Server Deployment
        context.setAttribute(javax.websocket.server.ServerContainer.class.getName(),jettyContainer);
    
        // Create Filter
        if(isEnabledViaContext(context.getServletContext(), ADD_DYNAMIC_FILTER_KEY, true))
        {
            WebSocketUpgradeFilter.configureContext(context);
        }
    
        return jettyContainer;
    }
    
    /**
     * @deprecated use {@link #configureContext(ServletContextHandler)} instead
     */
    @Deprecated
    public static ServerContainer configureContext(ServletContext context, ServletContextHandler jettyContext) throws ServletException
    {
        return configureContext(jettyContext);
    }

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext context) throws ServletException
    {
        if(!isEnabledViaContext(context, ENABLE_KEY, true))
        {
            return;
        }
        
        ContextHandler handler = ContextHandler.getContextHandler(context);

        if (handler == null)
        {
            throw new ServletException("Not running on Jetty, JSR-356 support unavailable");
        }

        if (!(handler instanceof ServletContextHandler))
        {
            throw new ServletException("Not running in Jetty ServletContextHandler, JSR-356 support unavailable");
        }

        ServletContextHandler jettyContext = (ServletContextHandler)handler;

        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(context.getClassLoader());
            
            // Create the Jetty ServerContainer implementation
            ServerContainer jettyContainer = configureContext(jettyContext);
    
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
                LOG.debug("Found {} classes",c.size());
            }
    
            // Now process the incoming classes
            Set<Class<? extends Endpoint>> discoveredExtendedEndpoints = new HashSet<>();
            Set<Class<?>> discoveredAnnotatedEndpoints = new HashSet<>();
            Set<Class<? extends ServerApplicationConfig>> serverAppConfigs = new HashSet<>();

            filterClasses(c,discoveredExtendedEndpoints,discoveredAnnotatedEndpoints,serverAppConfigs);

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Discovered {} extends Endpoint classes",discoveredExtendedEndpoints.size());
                LOG.debug("Discovered {} @ServerEndpoint classes",discoveredAnnotatedEndpoints.size());
                LOG.debug("Discovered {} ServerApplicationConfig classes",serverAppConfigs.size());
            }

            // Process the server app configs to determine endpoint filtering
            boolean wasFiltered = false;
            Set<ServerEndpointConfig> deployableExtendedEndpointConfigs = new HashSet<>();
            Set<Class<?>> deployableAnnotatedEndpoints = new HashSet<>();

            for (Class<? extends ServerApplicationConfig> clazz : serverAppConfigs)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("Found ServerApplicationConfig: {}",clazz);
                }
                try
                {
                    ServerApplicationConfig config = clazz.newInstance();

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
                catch (InstantiationException | IllegalAccessException e)
                {
                    throw new ServletException("Unable to instantiate: " + clazz.getName(),e);
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
                LOG.debug("Deploying {} ServerEndpointConfig(s)",deployableExtendedEndpointConfigs.size());
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
                LOG.debug("Deploying {} @ServerEndpoint(s)",deployableAnnotatedEndpoints.size());
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
        } finally {
            Thread.currentThread().setContextClassLoader(old);
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
