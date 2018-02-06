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

package org.eclipse.jetty.websocket.jsr356.tests.client;

import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketContainer;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketFrameHandler;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.jsr356.client.JavaxWebSocketClientContainer;
import org.eclipse.jetty.websocket.jsr356.tests.DummyChannel;
import org.eclipse.jetty.websocket.jsr356.tests.DummyEndpoint;
import org.eclipse.jetty.websocket.jsr356.tests.HandshakeRequestAdapter;
import org.eclipse.jetty.websocket.jsr356.tests.HandshakeResponseAdapter;
import org.junit.AfterClass;
import org.junit.BeforeClass;

public abstract class AbstractClientSessionTest
{
    protected static JavaxWebSocketSession session;
    protected static JavaxWebSocketContainer container;

    @BeforeClass
    public static void initSession() throws Exception
    {
        container = new JavaxWebSocketClientContainer();
        container.start();
        Object websocketPojo = new DummyEndpoint();
        HandshakeRequest handshakeRequest = new HandshakeRequestAdapter();
        HandshakeResponse handshakeResponse = new HandshakeResponseAdapter();
        JavaxWebSocketFrameHandler frameHandler =
                container.newFrameHandler(websocketPojo, container.getPolicy(), handshakeRequest, handshakeResponse, null);
        FrameHandler.Channel channel = new DummyChannel();
        String id = "dummy";
        EndpointConfig endpointConfig = null;
        session = new JavaxWebSocketSession(container,
                channel,
                frameHandler,
                handshakeRequest,
                handshakeResponse,
                id,
                endpointConfig);
        container.addManaged(session);
    }

    @AfterClass
    public static void stopContainer() throws Exception
    {
        container.stop();
    }
}
