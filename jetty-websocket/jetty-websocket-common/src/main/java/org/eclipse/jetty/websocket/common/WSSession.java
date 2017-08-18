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
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.WSPolicy;
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
     *
     * @param jettyEndpoint The Jetty EndPoint for this connection
     * @param executor The common Executor
     * @param bufferPool The common Byte BufferPool
     * @param objectFactory Object Factory for decorators (CDI)
     * @param policy The policy for this WebSocket connection
     * @param extensionStack The configured ExtensionStack
     * @param upgradeRequest The Handshake Upgrade Request used to establish this connection
     * @param upgradeResponse The Handshake Upgrade Response used to establish this connection
     */
    public WSSession(EndPoint jettyEndpoint, Executor executor, ByteBufferPool bufferPool,
                     DecoratedObjectFactory objectFactory,
                     WSPolicy policy, ExtensionStack extensionStack,
                     UpgradeRequest upgradeRequest, UpgradeResponse upgradeResponse)
    {
        super(jettyEndpoint, executor, bufferPool, objectFactory, policy, extensionStack, upgradeRequest, upgradeResponse);
    }

    @Override
    public void close(CloseStatus closeStatus)
    {
        // TODO
    }

    @Override
    public void close(int statusCode, String reason)
    {
        // TODO
    }

    @Override
    public String getProtocolVersion()
    {
        return getUpgradeRequest().getProtocolVersion();
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
