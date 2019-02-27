//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.CompletableFuture;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.common.WebSocketContainer;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.server.internal.DelegatedJettyServletUpgradeRequest;
import org.eclipse.jetty.websocket.server.internal.JettyWebSocketServerContainer;
import org.eclipse.jetty.websocket.server.internal.UpgradeResponseAdapter;
import org.eclipse.jetty.websocket.servlet.FrameHandlerFactory;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;

public class JettyServerFrameHandlerFactory
    extends JettyWebSocketFrameHandlerFactory
    implements FrameHandlerFactory, LifeCycle.Listener
{
    public static JettyServerFrameHandlerFactory ensureFactory(ServletContext servletContext)
        throws ServletException
    {
        ContextHandler contextHandler = ServletContextHandler.getServletContextHandler(servletContext, "Jetty Websocket");

        JettyServerFrameHandlerFactory factory = contextHandler.getBean(JettyServerFrameHandlerFactory.class);
        if (factory == null)
        {
            JettyWebSocketServerContainer container = new JettyWebSocketServerContainer(contextHandler);
            servletContext.setAttribute(WebSocketContainer.class.getName(), container);
            factory = new JettyServerFrameHandlerFactory(container);
            contextHandler.addManaged(factory);
            contextHandler.addLifeCycleListener(factory);
        }
        return factory;
    }

    public JettyServerFrameHandlerFactory(WebSocketContainer container)
    {
        super(container);
    }

    @Override
    public FrameHandler newFrameHandler(Object websocketPojo, ServletUpgradeRequest upgradeRequest, ServletUpgradeResponse upgradeResponse)
    {
        return super.newJettyFrameHandler(websocketPojo, new DelegatedJettyServletUpgradeRequest(upgradeRequest), new UpgradeResponseAdapter(upgradeResponse),
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
