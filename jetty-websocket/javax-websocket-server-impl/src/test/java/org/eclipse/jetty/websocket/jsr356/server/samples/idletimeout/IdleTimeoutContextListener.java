//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.jsr356.server.samples.idletimeout;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.DeploymentException;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;

/**
 * Example of adding a server WebSocket (extending {@link javax.websocket.Endpoint}) programmatically via config
 */
public class IdleTimeoutContextListener implements ServletContextListener
{
    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        /* do nothing */
    }

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        ServerContainer container = (ServerContainer)sce.getServletContext().getAttribute(ServerContainer.class.getName());
        // Build up a configuration with a specific path
        String path = "/idle-onopen-endpoint";
        ServerEndpointConfig.Builder builder = ServerEndpointConfig.Builder.create(OnOpenIdleTimeoutEndpoint.class, path);
        try
        {
            container.addEndpoint(builder.build());
        }
        catch (DeploymentException e)
        {
            throw new RuntimeException("Unable to add endpoint via config file", e);
        }
    }
}
