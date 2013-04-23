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

package org.eclipse.jetty.websocket.jsr356.server.samples.echo;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerContainer;

/**
 * Example of adding a server WebSocket (extending {@link Endpoint}) programmatically directly.
 * <p>
 * NOTE: this shouldn't work as the endpoint has no path associated with it.
 */
public class BasicEchoEndpointContextListener implements ServletContextListener
{
    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        /* do nothing */
    }

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        ServerContainer container = (ServerContainer)ContainerProvider.getWebSocketContainer();
        try
        {
            // Should fail as there is no path associated with this endpoint
            container.addEndpoint(BasicEchoEndpoint.class);
        }
        catch (DeploymentException e)
        {
            throw new RuntimeException("Unable to add endpoint directly",e);
        }
    }
}
