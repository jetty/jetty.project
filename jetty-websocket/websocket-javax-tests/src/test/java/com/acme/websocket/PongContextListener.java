//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package com.acme.websocket;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.DeploymentException;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;

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
            ServerEndpointConfig.Configurator config = new Config();
            container.addEndpoint(ServerEndpointConfig.Builder.create(PongMessageEndpoint.class, "/pong").configurator(config).build());
        }
        catch (DeploymentException e)
        {
            throw new RuntimeException("Unable to add endpoint directly", e);
        }
    }
}
