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

package org.eclipse.jetty.websocket.server.internal;

import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandler;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.server.FrameHandlerFactory;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;

public class ServerFrameHandlerFactory extends JettyWebSocketFrameHandlerFactory implements FrameHandlerFactory
{
    public ServerFrameHandlerFactory(WebSocketContainer container, WebSocketComponents components)
    {
        super(container, components);
    }

    @Override
    public FrameHandler newFrameHandler(Object webSocketEndPoint, ServerUpgradeRequest upgradeRequest, ServerUpgradeResponse upgradeResponse)
    {
        JettyWebSocketFrameHandler frameHandler = newJettyFrameHandler(webSocketEndPoint);
        frameHandler.setUpgradeRequest(new UpgradeRequestDelegate(upgradeRequest));
        frameHandler.setUpgradeResponse(new UpgradeResponseDelegate(upgradeResponse));
        return frameHandler;
    }
}
