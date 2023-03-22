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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.server.sockets.echo.BasicEchoSocket;

/**
 * Example of adding a server socket (annotated) programmatically directly with no config
 */
public class BasicEchoSocketContextListener implements ServletContextListener
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
            container.addEndpoint(BasicEchoSocket.class);
        }
        catch (DeploymentException e)
        {
            throw new RuntimeException("Unable to add endpoint directly", e);
        }
    }
}
