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

package org.eclipse.jetty.websocket.server.internal;

import javax.servlet.ServletContext;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandler;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.common.WebSocketContainer;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.servlet.FrameHandlerFactory;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;

public class JettyServerFrameHandlerFactory
    extends JettyWebSocketFrameHandlerFactory
    implements FrameHandlerFactory, LifeCycle.Listener
{
    public static JettyServerFrameHandlerFactory getFactory(ServletContext context)
    {
        ServletContextHandler contextHandler = ServletContextHandler.getServletContextHandler(context, "JettyServerFrameHandlerFactory");
        return contextHandler.getBean(JettyServerFrameHandlerFactory.class);
    }

    public JettyServerFrameHandlerFactory(WebSocketContainer container)
    {
        super(container);
    }

    @Override
    public FrameHandler newFrameHandler(Object websocketPojo, ServletUpgradeRequest upgradeRequest, ServletUpgradeResponse upgradeResponse)
    {
        JettyWebSocketFrameHandler frameHandler = super.newJettyFrameHandler(websocketPojo);
        frameHandler.setUpgradeRequest(new UpgradeRequestAdapter(upgradeRequest));
        frameHandler.setUpgradeResponse(new UpgradeResponseAdapter(upgradeResponse));
        return frameHandler;
    }

    @Override
    public void lifeCycleStopping(LifeCycle context)
    {
        ContextHandler contextHandler = (ContextHandler)context;
        JettyServerFrameHandlerFactory factory = contextHandler.getBean(JettyServerFrameHandlerFactory.class);
        if (factory != null)
        {
            contextHandler.removeBean(factory);
            LifeCycle.stop(factory);
        }
    }
}
