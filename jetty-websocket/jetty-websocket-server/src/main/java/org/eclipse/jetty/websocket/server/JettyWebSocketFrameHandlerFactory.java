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

import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.listeners.WebSocketConnectionListener;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.servlet.FrameHandlerFactory;

/**
 * Factory for websocket-core {@link FrameHandler} implementations suitable for
 * use with jetty-native websocket API.
 * <p>
 * Will create a {@link FrameHandler} suitable for use with classes/objects that are:
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
public class JettyWebSocketFrameHandlerFactory implements FrameHandlerFactory
{
    @Override
    public FrameHandler newFrameHandler(Object websocketPojo)
    {
        if(websocketPojo == null)
            return null;

        Class<?> websocketClazz =websocketPojo.getClass();
        WebSocket websocketAnno = websocketClazz.getAnnotation(WebSocket.class);
        if(websocketAnno != null)
        {
            return createAnnotatedFrameHandler(websocketAnno, websocketPojo);
        }

        if(WebSocketConnectionListener.class.isAssignableFrom(websocketClazz))
        {

        }

        return null;
    }

    private FrameHandler createAnnotatedFrameHandler(WebSocket websocketAnno, Object websocketPojo)
    {
        // TODO
        return null;
    }
}
