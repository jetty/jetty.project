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

package org.eclipse.jetty.websocket.tests;

import org.eclipse.jetty.websocket.common.WebSocketSessionImpl;
import org.eclipse.jetty.websocket.core.WebSocketLocalEndpoint;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.WebSocketRemoteEndpoint;
import org.eclipse.jetty.websocket.core.io.WebSocketCoreConnection;

public class UntrustedWSSession<T extends WebSocketCoreConnection> extends WebSocketSessionImpl<T> implements AutoCloseable
{
    private final UntrustedWSConnection untrustedConnection;
    private UntrustedWSEndpoint untrustedEndpoint;

    public UntrustedWSSession(T connection)
    {
        super(connection);
        connection.getGenerator().setValidating(false);
        this.untrustedConnection = new UntrustedWSConnection(connection);
    }

    @Override
    public void setWebSocketEndpoint(Object endpoint, WebSocketPolicy policy, WebSocketLocalEndpoint localEndpoint, WebSocketRemoteEndpoint remoteEndpoint)
    {
        this.untrustedEndpoint = (UntrustedWSEndpoint) localEndpoint;
        super.setWebSocketEndpoint(endpoint, policy, localEndpoint, remoteEndpoint);
    }

    public UntrustedWSConnection getUntrustedConnection()
    {
        return untrustedConnection;
    }

    public UntrustedWSEndpoint getUntrustedEndpoint()
    {
        return untrustedEndpoint;
    }
}
