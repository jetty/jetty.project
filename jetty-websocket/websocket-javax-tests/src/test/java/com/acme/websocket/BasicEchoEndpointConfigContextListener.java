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

package com.acme.websocket;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.DeploymentException;
import javax.websocket.server.ServerEndpointConfig;

public class BasicEchoEndpointConfigContextListener implements ServletContextListener
{
    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        /* do nothing */
    }

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        javax.websocket.server.ServerContainer container = (javax.websocket.server.ServerContainer)sce.getServletContext()
            .getAttribute(javax.websocket.server.ServerContainer.class.getName());
        if (container == null)
            throw new IllegalStateException("No Websocket ServerContainer in " + sce.getServletContext());

        // Build up a configuration with a specific path
        String path = "/echo";
        ServerEndpointConfig.Builder builder = ServerEndpointConfig.Builder.create(BasicEchoEndpoint.class, path);
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
