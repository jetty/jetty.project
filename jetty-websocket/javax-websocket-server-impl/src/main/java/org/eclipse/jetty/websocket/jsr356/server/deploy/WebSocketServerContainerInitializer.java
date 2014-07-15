//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;

@HandlesTypes(
{ ServerApplicationConfig.class, ServerEndpoint.class, Endpoint.class })
public class WebSocketServerContainerInitializer implements ServletContainerInitializer
{
    public static final String ENABLE_KEY = "org.eclipse.jetty.websocket.jsr356";
    private static final Logger LOG = Log.getLogger(WebSocketServerContainerInitializer.class);


    public static ServerContainer configureContext(ServletContextHandler context)
    {
        // Create Filter
        WebSocketUpgradeFilter filter = WebSocketUpgradeFilter.configureContext(context);

        // Store reference to the WebSocketUpgradeFilter
        context.setAttribute(WebSocketUpgradeFilter.class.getName(),filter);

        // Create the Jetty ServerContainer implementation
        ServerContainer jettyContainer = new ServerContainer(filter,filter.getFactory(),context.getServer().getThreadPool());
        context.addBean(jettyContainer);

        // Store a reference to the ServerContainer per javax.websocket spec 1.0 final section 6.4 Programmatic Server Deployment
        context.setAttribute(javax.websocket.server.ServerContainer.class.getName(),jettyContainer);

        return jettyContainer;
    }

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext context) throws ServletException
    {
        Object enable = context.getAttribute(ENABLE_KEY);
        
        // Disable if explicitly disabled
        if (TypeUtil.isFalse(enable))
        {
            if (c.isEmpty())
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("JSR-356 support disabled via attribute on context {} - {}",context.getContextPath(),context);
                }
            }
            else
            {
                LOG.warn("JSR-356 support disabled via attribute on context {} - {}",context.getContextPath(),context);
            }
            return;
        }
        
        // Disabled if not explicitly enabled and there are no discovered annotations or interfaces
        if (!TypeUtil.isTrue(enable) && c.isEmpty())
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("No JSR-356 annotations or interfaces discovered. JSR-356 support disabled",context.getContextPath(),context);
            }
            return;
        }

        ContextHandler handler = ContextHandler.getContextHandler(context);

        if (handler == null)
        {
            throw new ServletException("Not running on Jetty, JSR-356 support disabled");
        }

        if (!(handler instanceof ServletContextHandler))
        {
            throw new ServletException("Not running in Jetty ServletContextHandler, JSR-356 support disabled");
        }

        ServletContextHandler jettyContext = (ServletContextHandler)handler;

        // Create the Jetty ServerContainer implementation
        ServerContainer jettyContainer = configureContext(jettyContext);

        // Store a reference to the ServerContainer per javax.websocket spec 1.0 final section 6.4 Programmatic Server Deployment
        context.setAttribute(javax.websocket.server.ServerContainer.class.getName(),jettyContainer);

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
