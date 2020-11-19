//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.server.internal;

import javax.servlet.ServletContext;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandler;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.server.FrameHandlerFactory;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;

public class JettyServerFrameHandlerFactory extends JettyWebSocketFrameHandlerFactory implements FrameHandlerFactory, LifeCycle.Listener
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
    public FrameHandler newFrameHandler(Object websocketPojo, ServerUpgradeRequest upgradeRequest, ServerUpgradeResponse upgradeResponse)
    {
        JettyWebSocketFrameHandler frameHandler = super.newJettyFrameHandler(websocketPojo);
        frameHandler.setUpgradeRequest(new DelegatedServerUpgradeRequest(upgradeRequest));
        frameHandler.setUpgradeResponse(new DelegatedServerUpgradeResponse(upgradeResponse));
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
