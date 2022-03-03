//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.server.sockets.pong.PongMessageEndpoint;

/**
 * Example of adding a server WebSocket (extending {@link jakarta.websocket.Endpoint}) programmatically directly.
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
        ServerContainer container = (ServerContainer)sce.getServletContext().getAttribute(ServerContainer.class.getName());

        try
        {
            container.addEndpoint(ServerEndpointConfig.Builder.create(PongMessageEndpoint.class, "/ping").build());
            container.addEndpoint(ServerEndpointConfig.Builder.create(PongMessageEndpoint.class, "/pong").build());
        }
        catch (DeploymentException e)
        {
            throw new RuntimeException("Unable to add endpoint via config file", e);
        }
    }
}
