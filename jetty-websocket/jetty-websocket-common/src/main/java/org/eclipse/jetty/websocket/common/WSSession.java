//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.api.*;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.WSLocalEndpoint;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.handshake.UpgradeRequest;
import org.eclipse.jetty.websocket.core.handshake.UpgradeResponse;
import org.eclipse.jetty.websocket.core.io.WSConnection;

public class WSSession extends WSConnection implements Session
{
    /**
     * Create a WSConnection.
     * <p>
     * It is assumed that the WebSocket Upgrade Handshake has already
     * completed successfully before creating this connection.
     * </p>
     */
    public WSSession(EndPoint endp, Executor executor, ByteBufferPool bufferPool, DecoratedObjectFactory decoratedObjectFactory, WebSocketPolicy policy, ExtensionStack extensionStack, UpgradeRequest upgradeRequest, UpgradeResponse upgradeResponse, Object wsEndpoint, WSLocalEndpoint localEndpoint)
    {
        super(endp, executor, bufferPool, decoratedObjectFactory, policy, extensionStack, upgradeRequest, upgradeResponse, wsEndpoint, localEndpoint);
    }

    @Override
    public void close(CloseStatus closeStatus)
    {

    }

    @Override
    public void close(int statusCode, String reason)
    {

    }

    @Override
    public String getProtocolVersion()
    {
        return null;
    }

    @Override
    public RemoteEndpoint getRemote()
    {
        return null;
    }

    @Override
    public void setIdleTimeout(long ms)
    {

    }
}
