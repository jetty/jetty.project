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

package org.eclipse.jetty.websocket.jakarta.tests.client;

import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.jakarta.client.BasicClientEndpointConfig;
import org.eclipse.jetty.websocket.jakarta.client.JakartaWebSocketClientContainer;
import org.eclipse.jetty.websocket.jakarta.common.JakartaWebSocketContainer;
import org.eclipse.jetty.websocket.jakarta.common.JakartaWebSocketFrameHandler;
import org.eclipse.jetty.websocket.jakarta.common.JakartaWebSocketSession;
import org.eclipse.jetty.websocket.jakarta.common.UpgradeRequest;
import org.eclipse.jetty.websocket.jakarta.common.UpgradeRequestAdapter;
import org.eclipse.jetty.websocket.jakarta.tests.DummyEndpoint;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

public abstract class AbstractClientSessionTest
{
    protected static JakartaWebSocketSession session;
    protected static JakartaWebSocketContainer container;

    @BeforeAll
    public static void initSession() throws Exception
    {
        container = new JakartaWebSocketClientContainer();
        container.start();
        Object websocketPojo = new DummyEndpoint();
        UpgradeRequest upgradeRequest = new UpgradeRequestAdapter();
        JakartaWebSocketFrameHandler frameHandler = container.newFrameHandler(websocketPojo, upgradeRequest);
        CoreSession coreSession = new CoreSession.Empty();
        session = new JakartaWebSocketSession(container, coreSession, frameHandler, new BasicClientEndpointConfig());
    }

    @AfterAll
    public static void stopContainer() throws Exception
    {
        container.stop();
    }
}
