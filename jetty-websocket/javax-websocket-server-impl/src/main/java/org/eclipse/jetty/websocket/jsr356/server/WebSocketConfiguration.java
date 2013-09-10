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

import org.eclipse.jetty.annotations.AnnotationConfiguration;
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
 * WebSocket Server Configuration component.
 * <p>
 * This configuration will configure a context for JSR356 Websockets.
 * <p>
 * It is possible to disable specific contexts with an attribute <code>"org.eclipse.jetty.websocket.jsr356"</code> (set to <code>"false"</code>)
 * <p>
 * This attribute may be set on an individual context, or on the server to affect all deployed contexts.
 */
public class WebSocketConfiguration extends AbstractConfiguration
{
    public static final String ENABLE = "org.eclipse.jetty.websocket.jsr356";
    private static final Logger LOG = Log.getLogger(WebSocketConfiguration.class);

    /**
     * Create a ServerContainer properly, useful for embedded application use.
     * <p>
     * Notably, the cometd3 project uses this.
     * 
     * @param context
     *            the context to enable javax.websocket support filters on
     * @return the ServerContainer that was created
     */
    public static ServerContainer configureContext(ServletContextHandler context)
    {
        LOG.debug("Configure javax.websocket for WebApp {}",context);
        WebSocketUpgradeFilter filter = WebSocketUpgradeFilter.configureContext(context);

        // Create the Jetty ServerContainer implementation
        ServerContainer jettyContainer = new ServerContainer(filter,filter.getFactory());
        context.addBean(jettyContainer);

        // Store a reference to the ServerContainer per javax.websocket spec 1.0 final section 6.4 Programmatic Server Deployment
        context.setAttribute(javax.websocket.server.ServerContainer.class.getName(),jettyContainer);

        // Store reference to DiscoveredEndpoints
        context.setAttribute(DiscoveredEndpoints.class.getName(),new DiscoveredEndpoints());

        return jettyContainer;
    }

    public static boolean isJSR356Context(WebAppContext context)
    {
        Object enable = context.getAttribute(ENABLE);
        if (enable instanceof Boolean)
        {
            return ((Boolean)enable).booleanValue();
        }

        enable = context.getServer().getAttribute(ENABLE);
        if (enable instanceof Boolean)
        {
            return ((Boolean)enable).booleanValue();
        }

        return true;
    }

    @Override
    public void configure(WebAppContext context) throws Exception
    {
        if (isJSR356Context(context))
        {
            WebSocketConfiguration.configureContext(context);
        }
        else
        {
            LOG.debug("JSR-356 support disabled for WebApp {}",context);
        }
    }

    @Override
    public void preConfigure(WebAppContext context) throws Exception
    {
        if (isJSR356Context(context))
        {
            boolean scanningAdded = false;
            // Add the annotation scanning handlers (if annotation scanning enabled)
            for (Configuration config : context.getConfigurations())
            {
                if (config instanceof AnnotationConfiguration)
                {
                    AnnotationConfiguration annocfg = (AnnotationConfiguration)config;
                    annocfg.addDiscoverableAnnotationHandler(new ServerEndpointAnnotationHandler(context));
                    scanningAdded = true;
                }
            }
            LOG.debug("@ServerEndpoint scanning added: {}",scanningAdded);
        }
    }
}
