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

package org.eclipse.jetty.ee9.websocket.server.internal;

import jakarta.servlet.ServletContext;
import org.eclipse.jetty.ee9.websocket.common.JettyWebSocketFrameHandler;
import org.eclipse.jetty.ee9.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.server.FrameHandlerFactory;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;

public class JettyServerFrameHandlerFactory extends JettyWebSocketFrameHandlerFactory implements FrameHandlerFactory
{
    public static JettyServerFrameHandlerFactory getFactory(ServletContext servletContext)
    {
        JettyWebSocketServerContainer container = JettyWebSocketServerContainer.getContainer(servletContext);
        return (container == null) ? null : container.getBean(JettyServerFrameHandlerFactory.class);
    }

    public JettyServerFrameHandlerFactory(JettyWebSocketServerContainer container, WebSocketComponents components)
    {
        super(container, components);
    }

    @Override
    public FrameHandler newFrameHandler(Object websocketPojo, ServerUpgradeRequest upgradeRequest, ServerUpgradeResponse upgradeResponse)
    {
        JettyWebSocketFrameHandler frameHandler = super.newJettyFrameHandler(websocketPojo);
        frameHandler.setUpgradeRequest(new DelegatedServerUpgradeRequest(upgradeRequest));
        frameHandler.setUpgradeResponse(new DelegatedServerUpgradeResponse(upgradeResponse));
        return frameHandler;
    }
}
