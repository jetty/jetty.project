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

package org.eclipse.jetty.ee10.websocket.jakarta.common;

import java.util.HashMap;
import java.util.Map;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.EndpointConfig;
import org.eclipse.jetty.ee10.websocket.jakarta.common.decoders.AvailableDecoders;
import org.eclipse.jetty.ee10.websocket.jakarta.common.encoders.AvailableEncoders;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.junit.jupiter.api.AfterEach;

public abstract class AbstractJakartaWebSocketFrameHandlerTest
{
    protected DummyContainer container;
    private WebSocketComponents components;

    @AfterEach
    public void stopContainer() throws Exception
    {
        if (components != null)
        {
            components.stop();
            components = null;
        }

        if (container != null)
        {
            container.stop();
            container = null;
        }
    }

    protected AvailableEncoders encoders;
    protected AvailableDecoders decoders;
    protected Map<String, String> uriParams;
    protected EndpointConfig endpointConfig;
    protected CoreSession coreSession = new CoreSession.Empty()
    {

        @Override
        public WebSocketComponents getWebSocketComponents()
        {
            return components;
        }
    };

    public AbstractJakartaWebSocketFrameHandlerTest()
    {
        container = new DummyContainer();
        components = new WebSocketComponents();
        LifeCycle.start(container);
        LifeCycle.start(components);

        endpointConfig = ClientEndpointConfig.Builder.create().build();
        encoders = new AvailableEncoders(endpointConfig, coreSession.getWebSocketComponents());
        decoders = new AvailableDecoders(endpointConfig, coreSession.getWebSocketComponents());
        uriParams = new HashMap<>();
    }

    protected JakartaWebSocketFrameHandler newJakartaFrameHandler(Object websocket)
    {
        JakartaWebSocketFrameHandlerFactory factory = container.getFrameHandlerFactory();
        ConfiguredEndpoint endpoint = new ConfiguredEndpoint(websocket, endpointConfig);
        UpgradeRequest upgradeRequest = new UpgradeRequestAdapter();
        return factory.newJakartaWebSocketFrameHandler(endpoint, upgradeRequest);
    }
}
