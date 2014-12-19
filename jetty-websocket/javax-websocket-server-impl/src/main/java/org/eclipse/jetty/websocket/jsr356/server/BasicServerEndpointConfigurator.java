//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

/**
 * The Basic Configurator
 */
public class BasicServerEndpointConfigurator extends ContainerDefaultConfigurator
{
    private static final Logger LOG = Log.getLogger(BasicServerEndpointConfigurator.class);
    private final DecoratedObjectFactory objectFactory;
    
    public BasicServerEndpointConfigurator(WebSocketContainerScope containerScope)
    {
        super();
        this.objectFactory = containerScope.getObjectFactory();
    }

    @Override
    public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug(".getEndpointInstance({})",endpointClass);
        }
        
        try
        {
            return objectFactory.createInstance(endpointClass);
        }
        catch (IllegalAccessException e)
        {
            throw new InstantiationException(String.format("%s: %s",e.getClass().getName(),e.getMessage()));
        }
    }
}