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

package org.eclipse.jetty.websocket.core.server;

import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.io.WebSocketCoreConnection;

public interface WebSocketConnectionFactory extends ConnectionFactory
{
    WebSocketPolicy getPolicy();

    WebSocketCoreConnection newConnection(Connector connector, EndPoint endPoint, WebSocketCoreSession session);

    @Override
    default Connection newConnection(Connector connector, EndPoint endPoint)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    default String getProtocol()
    {
        return "ws";
    }

    @Override
    default List<String> getProtocols()
    {
        return Collections.singletonList(getProtocol());
    }

    ByteBufferPool getBufferPool();
}
