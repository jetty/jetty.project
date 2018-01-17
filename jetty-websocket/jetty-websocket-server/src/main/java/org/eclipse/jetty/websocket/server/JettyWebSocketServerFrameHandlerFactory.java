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

package org.eclipse.jetty.websocket.server;

import java.util.concurrent.Executors;

import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.common.FrameHandlerFactory;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;

/**
 * Factory for websocket-core {@link FrameHandler} implementations suitable for
 * use with jetty-native websocket API.
 * <p>
 * Will create a {@link FrameHandler} suitable for use with classes/objects that:
 * </p>
 * <ul>
 * <li>Is &#64;{@link org.eclipse.jetty.websocket.api.annotations.WebSocket} annotated</li>
 * <li>Extends {@link org.eclipse.jetty.websocket.api.listeners.WebSocketAdapter}</li>
 * <li>Implements {@link org.eclipse.jetty.websocket.api.listeners.WebSocketListener}</li>
 * <li>Implements {@link org.eclipse.jetty.websocket.api.listeners.WebSocketConnectionListener}</li>
 * <li>Implements {@link org.eclipse.jetty.websocket.api.listeners.WebSocketPartialListener}</li>
 * <li>Implements {@link org.eclipse.jetty.websocket.api.listeners.WebSocketPingPongListener}</li>
 * <li>Implements {@link org.eclipse.jetty.websocket.api.listeners.WebSocketFrameListener}</li>
 * </ul>
 */
public class JettyWebSocketServerFrameHandlerFactory extends JettyWebSocketFrameHandlerFactory implements FrameHandlerFactory
{
    public JettyWebSocketServerFrameHandlerFactory()
    {
        super(Executors.newFixedThreadPool(10)); // TODO: get from container (somehow)
    }

    @Override
    public FrameHandler newFrameHandler(Object websocketPojo, WebSocketPolicy policy, HandshakeRequest handshakeRequest, HandshakeResponse handshakeResponse)
    {
        if(websocketPojo == null)
            return null;

        return super.createLocalEndpoint(websocketPojo, policy, handshakeRequest, handshakeResponse);
    }
}
