//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.server.internal.UpgradeRequestAdapter;
import org.eclipse.jetty.websocket.server.internal.UpgradeResponseAdapter;
import org.eclipse.jetty.websocket.servlet.FrameHandlerFactory;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.servlet.ServletContext;

public class JettyServerFrameHandlerFactory
    extends JettyWebSocketFrameHandlerFactory
    implements FrameHandlerFactory, LifeCycle.Listener
{
    public static JettyServerFrameHandlerFactory ensureFactory(ServletContext servletContext)
    {
        ContextHandler contextHandler = ContextHandler.getContextHandler(servletContext);
        JettyServerFrameHandlerFactory factory = contextHandler.getBean(JettyServerFrameHandlerFactory.class);
        if (factory == null)
        {
            Executor executor = (Executor)servletContext
                .getAttribute("org.eclipse.jetty.server.Executor");
            if (executor == null)
                executor = contextHandler.getServer().getThreadPool();

            factory = new JettyServerFrameHandlerFactory(executor);
            contextHandler.addManaged(factory);
            contextHandler.addLifeCycleListener(factory);
        }
        return factory;
    }

    public JettyServerFrameHandlerFactory(Executor executor)
    {
        super(executor);
    }

    @Override
    public FrameHandler newFrameHandler(Object websocketPojo, ServletUpgradeRequest upgradeRequest, ServletUpgradeResponse upgradeResponse)
    {
        return super.newJettyFrameHandler(websocketPojo, new UpgradeRequestAdapter(upgradeRequest), new UpgradeResponseAdapter(upgradeResponse),
            new CompletableFuture<>());
    }

    @Override
    public void lifeCycleStopping(LifeCycle context)
    {
        ContextHandler contextHandler = (ContextHandler) context;
        JettyServerFrameHandlerFactory factory = contextHandler.getBean(JettyServerFrameHandlerFactory.class);
        if (factory != null)
        {
            contextHandler.removeBean(factory);
            LifeCycle.stop(factory);
        }
    }
}
