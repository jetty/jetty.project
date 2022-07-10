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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.client;

import org.eclipse.jetty.ee9.websocket.jakarta.client.internal.BasicClientEndpointConfig;
import org.eclipse.jetty.ee9.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;
import org.eclipse.jetty.ee9.websocket.jakarta.common.JakartaWebSocketContainer;
import org.eclipse.jetty.ee9.websocket.jakarta.common.JakartaWebSocketFrameHandler;
import org.eclipse.jetty.ee9.websocket.jakarta.common.JakartaWebSocketSession;
import org.eclipse.jetty.ee9.websocket.jakarta.common.UpgradeRequest;
import org.eclipse.jetty.ee9.websocket.jakarta.common.UpgradeRequestAdapter;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.DummyEndpoint;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

public abstract class AbstractClientSessionTest
{
    protected static JakartaWebSocketSession session;
    protected static JakartaWebSocketContainer container;
    protected static WebSocketComponents components;

    @BeforeAll
    public static void initSession() throws Exception
    {
        container = new JakartaWebSocketClientContainer();
        container.start();
        components = new WebSocketComponents();
        components.start();
        Object websocketPojo = new DummyEndpoint();
        UpgradeRequest upgradeRequest = new UpgradeRequestAdapter();
        JakartaWebSocketFrameHandler frameHandler = container.newFrameHandler(websocketPojo, upgradeRequest);
        CoreSession coreSession = new CoreSession.Empty()
        {
            @Override
            public WebSocketComponents getWebSocketComponents()
            {
                return components;
            }
        };
        session = new JakartaWebSocketSession(container, coreSession, frameHandler, new BasicClientEndpointConfig());
    }

    @AfterAll
    public static void stopContainer() throws Exception
    {
        container.stop();
    }
}
