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

import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.junit.AfterClass;
import org.junit.BeforeClass;

public abstract class AbstractSessionTest
{
    protected static JavaxWebSocketSession session;
    protected static JavaxWebSocketContainer container;

    @BeforeClass
    public static void initSession() throws Exception
    {
        container = new DummyContainer(WebSocketPolicy.newClientPolicy());
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

    public static class DummyEndpoint extends Endpoint
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
        }
    }
}
