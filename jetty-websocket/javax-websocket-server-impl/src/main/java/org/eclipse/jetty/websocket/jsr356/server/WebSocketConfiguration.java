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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.AbstractConfiguration;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;

/**
 * WebSocket Server Configuration component
 */
public class WebSocketConfiguration extends AbstractConfiguration
{
    public static final String JAVAX_WEBSOCKET_SERVER_CONTAINER = "javax.websocket.server.ServerContainer";
    private static final Logger LOG = Log.getLogger(WebSocketConfiguration.class);

    @Override
    public void configure(WebAppContext context) throws Exception
    {
        WebSocketUpgradeFilter filter = new WebSocketUpgradeFilter();
        FilterHolder fholder = new FilterHolder(filter);
        fholder.setName("Jetty_WebSocketUpgradeFilter");
        fholder.setDisplayName("WebSocket Upgrade Filter");
        String pathSpec = "/*";
        context.addFilter(fholder,pathSpec,EnumSet.of(DispatcherType.REQUEST));
        LOG.debug("Adding {} mapped to {} to {}",filter,pathSpec,context);

        ServerContainer container = new ServerContainer(filter);
        filter.setWebSocketServerFactoryListener(container);
        context.setAttribute(JAVAX_WEBSOCKET_SERVER_CONTAINER,container);
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
