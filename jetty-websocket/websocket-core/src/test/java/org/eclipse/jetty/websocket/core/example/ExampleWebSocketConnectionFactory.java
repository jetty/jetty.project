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

package org.eclipse.jetty.websocket.core.example;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketChannel;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.io.WebSocketConnection;
import org.eclipse.jetty.websocket.core.server.WebSocketConnectionFactory;

class ExampleWebSocketConnectionFactory extends ContainerLifeCycle implements WebSocketConnectionFactory
{
    WebSocketExtensionRegistry extensionRegistry = new WebSocketExtensionRegistry();
    WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER,extensionRegistry);
    ByteBufferPool bufferPool = new MappedByteBufferPool();

    @Override
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    public WebSocketConnection newConnection(Connector connector, EndPoint endPoint, WebSocketChannel session)
    {
        return new WebSocketConnection(
                endPoint,
                connector.getExecutor(),
                bufferPool,
                session);
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }
}
