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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.handshake.UpgradeRequest;
import org.eclipse.jetty.websocket.core.handshake.UpgradeResponse;
import org.eclipse.jetty.websocket.core.io.SuspendToken;
import org.eclipse.jetty.websocket.core.io.WebSocketCoreConnection;

public class WebSocketSessionImpl<
        P extends ContainerLifeCycle,
        C extends WebSocketCoreConnection,
        L extends LocalEndpointImpl,
        R extends RemoteEndpointImpl>
        extends WebSocketCoreSession<P,C,L,R> implements Session
{
    public WebSocketSessionImpl(P container, C connection)
    {
        super(container, connection);
        connection.setSession(this);
    }

    public void setFuture(CompletableFuture<Session> fut)
    {
        // TODO do we still need this?
    }

    @Override
    public void setWebSocketEndpoint(Object endpoint, WebSocketPolicy policy, L localEndpoint, R remoteEndpoint)
    {
        super.setWebSocketEndpoint(endpoint, policy, localEndpoint, remoteEndpoint);
    }

    private void closeRemote()
    {
        remoteEndpoint.close();
    }

    @Override
    public void close()
    {
        closeRemote();
        super.close(WebSocketConstants.NORMAL, null, Callback.NOOP);
    }

    @Override
    public void close(CloseStatus closeStatus)
    {
        closeRemote();
        super.close(closeStatus, Callback.NOOP);
    }

    @Override
    public void close(int statusCode, String reason)
    {
        closeRemote();
        super.close(statusCode, reason, Callback.NOOP);
    }

    @Override
    public void close(StatusCode statusCode, String reason)
    {
        this.close(statusCode.getCode(), reason);
    }

    @Override
    public void disconnect() throws IOException
    {
        getConnection().disconnect();
    }

    @Override
    public long getIdleTimeout()
    {
        return getConnection().getIdleTimeout();
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return getConnection().getLocalAddress();
    }

    @Override
    public String getProtocolVersion()
    {
        return getUpgradeRequest().getProtocolVersion();
    }

    @Override
    public RemoteEndpoint getRemote()
    {
        return remoteEndpoint;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return getConnection().getRemoteAddress();
    }

    @Override
    public UpgradeRequest getUpgradeRequest()
    {
        return getConnection().getUpgradeRequest();
    }

    @Override
    public UpgradeResponse getUpgradeResponse()
    {
        return getConnection().getUpgradeResponse();
    }

    @Override
    public boolean isOpen()
    {
        return getConnection().isOpen();
    }

    @Override
    public boolean isSecure()
    {
        return getConnection().isSecure();
    }

    @Override
    public void open()
    {
        remoteEndpoint.open();
        super.open();
    }

    @Override
    public void setIdleTimeout(long ms)
    {
        getConnection().setMaxIdleTimeout(ms);
    }

    @Override
    public SuspendToken suspend()
    {
        return getConnection().suspend();
    }
}
