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

package org.eclipse.jetty.websocket.common;

import org.eclipse.jetty.websocket.api.CloseStatus;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;

public class WebSocketSessionImpl implements Session
{
    private final WebSocketPolicy sessionPolicy;
    private final JettyWebSocketRemoteEndpoint remoteEndpoint;
    private final UpgradeRequest upgradeRequest;
    private final UpgradeResponse upgradeResponse;

    public WebSocketSessionImpl(
        WebSocketPolicy sessionPolicy,
        JettyWebSocketRemoteEndpoint remoteEndpoint,
        UpgradeRequest upgradeRequest,
        UpgradeResponse upgradeResponse)
    {
        this.sessionPolicy = sessionPolicy;
        this.remoteEndpoint = remoteEndpoint;
        this.upgradeRequest = upgradeRequest;
        this.upgradeResponse = upgradeResponse;
    }

    @Override
    public void close()
    {
        remoteEndpoint.close();
    }

    @Override
    public void close(CloseStatus closeStatus)
    {
        remoteEndpoint.close(closeStatus.getCode(), closeStatus.getPhrase());
    }

    @Override
    public void close(int statusCode, String reason)
    {
        remoteEndpoint.close(statusCode, reason);
    }

    @Override
    public long getIdleTimeout()
    {
        return remoteEndpoint.getCoreSession().getIdleTimeout().toMillis();
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        // TODO how do setters on this get reflected on the CoreSession?
        return sessionPolicy;
    }

    @Override
    public String getProtocolVersion()
    {
        return upgradeRequest.getProtocolVersion();
    }

    @Override
    public JettyWebSocketRemoteEndpoint getRemote()
    {
        return remoteEndpoint;
    }

    @Override
    public boolean isOpen()
    {
        return remoteEndpoint.getCoreSession().isOpen();
    }

    @Override
    public boolean isSecure()
    {
        return upgradeRequest.isSecure();
    }

    @Override
    public void setIdleTimeout(long ms)
    {
        remoteEndpoint.getCoreSession().setIdleTimeout(Duration.ofMillis(ms));
    }

    @Override
    public void disconnect() throws IOException
    {

    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return null;
    }

    @Override
    public UpgradeRequest getUpgradeRequest()
    {
        return null;
    }

    @Override
    public UpgradeResponse getUpgradeResponse()
    {
        return null;
    }

    @Override
    public SuspendToken suspend()
    {
        return null;
    }

    @Override
    public String toString()
    {
        return String.format("WebSocketSessionImpl[%s,to=%,d,%s]", sessionPolicy.getBehavior(), sessionPolicy.getIdleTimeout(), remoteEndpoint.getCoreSession());
    }
}
