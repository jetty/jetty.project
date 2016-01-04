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

package org.eclipse.jetty.cdi.websocket;

import javax.enterprise.context.Destroyed;
import javax.enterprise.context.Initialized;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.BeforeBeanDiscovery;
import javax.enterprise.inject.spi.Extension;

import org.eclipse.jetty.cdi.websocket.annotation.WebSocketScope;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Register the various WebSocket specific components for CDI
 */
public class WebSocketScopeExtension implements Extension
{
    private static final Logger LOG = Log.getLogger(WebSocketScopeExtension.class);

    public void addScope(@Observes final BeforeBeanDiscovery event)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("addScope()");
        }
        // Add our scope
        event.addScope(WebSocketScope.class,true,false);
    }

    public void registerContext(@Observes final AfterBeanDiscovery event)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("registerContext()");
        }
        // Register our context
        event.addContext(new WebSocketScopeContext());
    }

    public void logWsScopeInit(@Observes @Initialized(WebSocketScope.class) org.eclipse.jetty.websocket.api.Session sess)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Initialized @WebSocketScope - {}",sess);
        }
    }

    public void logWsScopeDestroyed(@Observes @Destroyed(WebSocketScope.class) org.eclipse.jetty.websocket.api.Session sess)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Destroyed @WebSocketScope - {}",sess);
        }
    }
}
