//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356;

import org.eclipse.jetty.websocket.common.UpgradeRequest;
import org.eclipse.jetty.websocket.common.UpgradeResponse;
import org.eclipse.jetty.websocket.core.DummyCoreSession;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import javax.websocket.EndpointConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public abstract class AbstractJavaxWebSocketFrameHandlerTest
{
    protected static WebSocketPolicy clientPolicy = new WebSocketPolicy();
    protected static DummyContainer container;
    
    @BeforeAll
    public static void initContainer() throws Exception
    {
        container = new DummyContainer(clientPolicy);
        container.start();
    }
    
    @AfterAll
    public static void stopContainer() throws Exception
    {
        container.stop();
    }


    protected AvailableEncoders encoders;
    protected AvailableDecoders decoders;
    protected Map<String, String> uriParams = new HashMap<>();
    protected EndpointConfig endpointConfig;
    protected FrameHandler.CoreSession channel = new DummyCoreSession();
    
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
        UpgradeResponse upgradeResponse = new UpgradeResponseAdapter();

        JavaxWebSocketFrameHandler localEndpoint = factory.newJavaxFrameHandler(endpoint,
                container.getPolicy(), upgradeRequest, upgradeResponse, new CompletableFuture<>());

        return localEndpoint;
    }
}
