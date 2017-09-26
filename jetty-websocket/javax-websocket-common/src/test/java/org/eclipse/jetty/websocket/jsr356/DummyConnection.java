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

package org.eclipse.jetty.websocket.jsr356;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.handshake.UpgradeRequest;
import org.eclipse.jetty.websocket.core.handshake.UpgradeRequestAdapter;
import org.eclipse.jetty.websocket.core.handshake.UpgradeResponse;
import org.eclipse.jetty.websocket.core.handshake.UpgradeResponseAdapter;
import org.eclipse.jetty.websocket.jsr356.io.JavaxWebSocketConnection;

public class DummyConnection extends JavaxWebSocketConnection
{
    public static DummyConnection from(JavaxWebSocketContainer container, URI requestURI)
    {
        EndPoint endPoint = new ByteArrayEndPoint();
        Executor executor = container.getExecutor();
        ByteBufferPool bufferPool = container.getBufferPool();
        DecoratedObjectFactory objectFactory = new DecoratedObjectFactory();
        WebSocketPolicy policy = container.getPolicy();
        UpgradeRequest request = new UpgradeRequestAdapter(requestURI);
        UpgradeResponse response = new UpgradeResponseAdapter();
        ExtensionStack extensionStack = new ExtensionStack(new WebSocketExtensionRegistry());
        List<ExtensionConfig> configs = Collections.emptyList();
        extensionStack.negotiate(objectFactory, policy, bufferPool, configs);

        return new DummyConnection(endPoint, executor, bufferPool, objectFactory, policy, extensionStack, request, response);
    }

    /**
     * Create a WSConnection.
     * <p>
     * It is assumed that the WebSocket Upgrade Handshake has already
     * completed successfully before creating this connection.
     * </p>
     */
    public DummyConnection(EndPoint endp,
                           Executor executor,
                           ByteBufferPool bufferPool,
                           DecoratedObjectFactory decoratedObjectFactory,
                           WebSocketPolicy policy,
                           ExtensionStack extensionStack,
                           UpgradeRequest upgradeRequest,
                           UpgradeResponse upgradeResponse)
    {
        super(endp, executor, bufferPool, decoratedObjectFactory, policy, extensionStack, upgradeRequest, upgradeResponse);
    }
}
