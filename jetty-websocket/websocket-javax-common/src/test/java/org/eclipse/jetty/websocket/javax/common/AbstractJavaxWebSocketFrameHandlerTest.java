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

package org.eclipse.jetty.websocket.javax.common;

import java.util.HashMap;
import java.util.Map;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.javax.common.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.javax.common.encoders.AvailableEncoders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

public abstract class AbstractJavaxWebSocketFrameHandlerTest
{
    protected static DummyContainer container;
    private static WebSocketComponents components;

    @BeforeAll
    public static void initContainer() throws Exception
    {
        container = new DummyContainer();
        container.start();
        components = new WebSocketComponents();
        components.start();
    }

    @AfterAll
    public static void stopContainer() throws Exception
    {
        components.stop();
        container.stop();
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

    public AbstractJavaxWebSocketFrameHandlerTest()
    {
        endpointConfig = ClientEndpointConfig.Builder.create().build();
        encoders = new AvailableEncoders(endpointConfig, coreSession.getWebSocketComponents());
        decoders = new AvailableDecoders(endpointConfig, coreSession.getWebSocketComponents());
        uriParams = new HashMap<>();
    }

    protected JavaxWebSocketFrameHandler newJavaxFrameHandler(Object websocket)
    {
        JavaxWebSocketFrameHandlerFactory factory = container.getFrameHandlerFactory();
        ConfiguredEndpoint endpoint = new ConfiguredEndpoint(websocket, endpointConfig);
        UpgradeRequest upgradeRequest = new UpgradeRequestAdapter();
        return factory.newJavaxWebSocketFrameHandler(endpoint, upgradeRequest);
    }
}
