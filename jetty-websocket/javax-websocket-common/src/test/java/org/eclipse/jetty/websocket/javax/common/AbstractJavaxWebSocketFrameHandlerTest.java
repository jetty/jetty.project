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

package org.eclipse.jetty.websocket.javax.common;

import java.util.HashMap;
import java.util.Map;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.javax.common.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.javax.common.encoders.AvailableEncoders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

public abstract class AbstractJavaxWebSocketFrameHandlerTest
{
    protected static DummyContainer container;

    @BeforeAll
    public static void initContainer() throws Exception
    {
        container = new DummyContainer();
        container.start();
    }

    @AfterAll
    public static void stopContainer() throws Exception
    {
        container.stop();
    }

    protected AvailableEncoders encoders;
    protected AvailableDecoders decoders;
    protected Map<String, String> uriParams;
    protected EndpointConfig endpointConfig;
    protected FrameHandler.CoreSession coreSession = new FrameHandler.CoreSession.Empty();

    public AbstractJavaxWebSocketFrameHandlerTest()
    {
        endpointConfig = new BasicEndpointConfig();
        encoders = new AvailableEncoders(endpointConfig);
        decoders = new AvailableDecoders(endpointConfig);
        uriParams = new HashMap<>();
    }

    protected JavaxWebSocketFrameHandler newJavaxFrameHandler(Object websocket)
    {
        JavaxWebSocketFrameHandlerFactory factory = container.getFrameHandlerFactory();
        BasicEndpointConfig config = new BasicEndpointConfig();
        ConfiguredEndpoint endpoint = new ConfiguredEndpoint(websocket, config);
        UpgradeRequest upgradeRequest = new UpgradeRequestAdapter();

        JavaxWebSocketFrameHandler localEndpoint = factory.newJavaxWebSocketFrameHandler(endpoint, upgradeRequest);

        return localEndpoint;
    }
}
