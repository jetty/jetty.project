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

package org.eclipse.jetty.websocket.jsr356.server;

import java.util.EnumSet;

import javax.servlet.DispatcherType;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.AbstractConfiguration;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.jsr356.server.deploy.DiscoveredEndpoints;
import org.eclipse.jetty.websocket.jsr356.server.deploy.ServerEndpointAnnotationHandler;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;

/**
 * WebSocket Server Configuration component
 */
public class WebSocketConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = Log.getLogger(WebSocketConfiguration.class);

    public static ServerContainer configureContext(ServletContextHandler context)
    {
        WebSocketUpgradeFilter filter = new WebSocketUpgradeFilter();
        FilterHolder fholder = new FilterHolder(filter);
        fholder.setName("Jetty_WebSocketUpgradeFilter");
        fholder.setDisplayName("WebSocket Upgrade Filter");
        String pathSpec = "/*";
        context.addFilter(fholder,pathSpec,EnumSet.of(DispatcherType.REQUEST));
        LOG.debug("Adding {} mapped to {} to {}",filter,pathSpec,context);

        // Store reference to the WebSocketUpgradeFilter
        context.setAttribute(WebSocketUpgradeFilter.class.getName(),filter);
        
        // Store reference to DiscoveredEndpoints
        context.setAttribute(DiscoveredEndpoints.class.getName(),new DiscoveredEndpoints());

        // Create the Jetty ServerContainer implementation
        ServerContainer jettyContainer = new ServerContainer(filter);
        filter.setWebSocketServerFactoryListener(jettyContainer);
        context.addBean(jettyContainer,true);

        // Store a reference to the ServerContainer per javax.websocket spec 1.0 final section 6.4 Programmatic Server Deployment
        context.setAttribute(javax.websocket.server.ServerContainer.class.getName(),jettyContainer);
        
        return jettyContainer;
    }

    @Override
    public void configure(WebAppContext context) throws Exception
    {
        WebSocketConfiguration.configureContext(context);
    }

    @Override
    public void preConfigure(WebAppContext context) throws Exception
    {
        // Add the annotation scanning handlers (if annotation scanning enabled)
        for (Configuration config : context.getConfigurations())
        {
            if (config instanceof AnnotationConfiguration)
            {
                AnnotationConfiguration annocfg = (AnnotationConfiguration)config;
                annocfg.addDiscoverableAnnotationHandler(new ServerEndpointAnnotationHandler(context));
            }
        }
    }
}
