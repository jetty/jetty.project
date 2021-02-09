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

package org.eclipse.jetty.websocket.jsr356.server.samples.pong;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.DeploymentException;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;
import javax.websocket.server.ServerEndpointConfig.Configurator;

public class PongContextListener implements ServletContextListener
{
    public static class Config extends ServerEndpointConfig.Configurator
    {
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            sec.getUserProperties().put("path", sec.getPath());
            super.modifyHandshake(sec, request, response);
        }
    }

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
            Configurator config = new Config();

            // Use manually declared Configurator
            container.addEndpoint(ServerEndpointConfig.Builder.create(PongMessageEndpoint.class, "/ping").configurator(config).build());
            container.addEndpoint(ServerEndpointConfig.Builder.create(PongMessageEndpoint.class, "/pong").configurator(config).build());
            // Use annotation declared Configurator
            container.addEndpoint(ServerEndpointConfig.Builder.create(PongSocket.class, "/ping-socket").build());
        }
        catch (DeploymentException e)
        {
            throw new RuntimeException("Unable to add endpoint directly", e);
        }
    }
}
