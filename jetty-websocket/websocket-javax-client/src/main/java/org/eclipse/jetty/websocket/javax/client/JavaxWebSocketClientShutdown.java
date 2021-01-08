//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.client;

import java.util.Set;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;

import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.javax.client.internal.JavaxWebSocketClientContainer;

public class JavaxWebSocketClientShutdown extends ContainerLifeCycle implements ServletContainerInitializer, ServletContextListener
{
    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
    {
        JavaxWebSocketClientContainer.SHUTDOWN_CONTAINER.compareAndSet(null, this);
        ctx.addListener(this);
    }

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        LifeCycle.start(this);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        LifeCycle.stop(this);
        removeBeans();
    }
}
