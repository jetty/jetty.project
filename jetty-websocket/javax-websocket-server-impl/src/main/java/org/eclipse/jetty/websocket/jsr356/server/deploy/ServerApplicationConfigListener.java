//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.HandlesTypes;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerApplicationConfig;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.WebSocketConfiguration;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;

@HandlesTypes(
{ ServerApplicationConfig.class, Endpoint.class })
public class ServerApplicationConfigListener implements ServletContainerInitializer
{
    private static final Logger LOG = Log.getLogger(ServerApplicationConfigListener.class);

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
    {
        if (!WebSocketConfiguration.isJSR356Context(WebAppContext.getCurrentWebAppContext()))
            return;
        
        WebSocketUpgradeFilter filter = (WebSocketUpgradeFilter)ctx.getAttribute(WebSocketUpgradeFilter.class.getName());
        if (filter == null)
        {
            LOG.warn("Required attribute not available: " + WebSocketUpgradeFilter.class.getName());
            return;
        }

        DiscoveredEndpoints discovered = (DiscoveredEndpoints)ctx.getAttribute(DiscoveredEndpoints.class.getName());
        if (discovered == null)
        {
            LOG.warn("Required attribute not available: " + DiscoveredEndpoints.class.getName());
            return;
        }

        LOG.debug("Found {} classes",c.size());
        LOG.debug("Discovered: {}",discovered);

        // First add all of the Endpoints
        addEndpoints(c,discovered);

        // Now process the ServerApplicationConfig entries
        ServerContainer container = (ServerContainer)ctx.getAttribute(javax.websocket.server.ServerContainer.class.getName());
        Set<Class<? extends Endpoint>> archiveSpecificExtendEndpoints = new HashSet<>();
        Set<Class<?>> archiveSpecificAnnotatedEndpoints = new HashSet<>();
        List<Class<? extends ServerApplicationConfig>> serverAppConfigs = filterServerApplicationConfigs(c);

        if(serverAppConfigs.size() >= 1) {
        for (Class<? extends ServerApplicationConfig> clazz : filterServerApplicationConfigs(c))
        {
            LOG.debug("Found ServerApplicationConfig: {}",clazz);
            try
            {
                ServerApplicationConfig config = (ServerApplicationConfig)clazz.newInstance();
                URI archiveURI = DiscoveredEndpoints.getArchiveURI(clazz);
                archiveSpecificExtendEndpoints.clear();
                archiveSpecificAnnotatedEndpoints.clear();
                discovered.getArchiveSpecificExtendedEndpoints(archiveURI,archiveSpecificExtendEndpoints);
                discovered.getArchiveSpecificAnnnotatedEndpoints(archiveURI,archiveSpecificAnnotatedEndpoints);

                Set<ServerEndpointConfig> seconfigs = config.getEndpointConfigs(archiveSpecificExtendEndpoints);
                if (seconfigs != null)
                {
                    for (ServerEndpointConfig sec : seconfigs)
                    {
                        container.addEndpoint(sec);
                    }
                }
                Set<Class<?>> annotatedClasses = config.getAnnotatedEndpointClasses(archiveSpecificAnnotatedEndpoints);
                if (annotatedClasses != null)
                {
                    for (Class<?> annotatedClass : annotatedClasses)
                    {
                        container.addEndpoint(annotatedClass);
                    }
                }
            }
            catch (InstantiationException | IllegalAccessException e)
            {
                throw new ServletException("Unable to instantiate: " + clazz.getName(),e);
            }
            catch (DeploymentException e)
            {
                throw new ServletException(e);
            }
        }
        } else {
            // Default behavior (no ServerApplicationConfigs found)
            // Note: it is impossible to determine path of "extends Endpoint" discovered classes
            for(Class<?> annotatedClass: discovered.getAnnotatedEndpoints())
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
    private List<Class<? extends ServerApplicationConfig>> filterServerApplicationConfigs(Set<Class<?>> c)
    {
        List<Class<? extends ServerApplicationConfig>> configs = new ArrayList<>();
        for (Class<?> clazz : c)
        {
            if (ServerApplicationConfig.class.isAssignableFrom(clazz))
            {
                configs.add((Class<? extends ServerApplicationConfig>)clazz);
            }
        }
        return configs;
    }

    @SuppressWarnings("unchecked")
    private void addEndpoints(Set<Class<?>> c, DiscoveredEndpoints discovered)
    {
        for (Class<?> clazz : c)
        {
            if (Endpoint.class.isAssignableFrom(clazz))
            {
                discovered.addExtendedEndpoint((Class<? extends Endpoint>)clazz);
            }
        }
    }
}
