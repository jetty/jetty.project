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
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.WebSocketLocalEndpoint;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.WebSocketRemoteEndpoint;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.io.SuspendToken;

public class WebSocketSessionImpl extends WebSocketCoreSession implements Session
{
    private final UpgradeRequest upgradeRequest;
    private final UpgradeResponse upgradeResponse;

    public WebSocketSessionImpl(WebSocketLocalEndpoint localEndpoint,
                                WebSocketRemoteEndpoint remoteEndpoint,
                                WebSocketPolicy policy,
                                ExtensionStack extensionStack,
                                UpgradeRequest upgradeRequest,
                                UpgradeResponse upgradeResponse)
    {
        super(localEndpoint, remoteEndpoint, policy, extensionStack);
        this.upgradeRequest = upgradeRequest;
        this.upgradeResponse = upgradeResponse;
    }

    @Override
    public LocalEndpointImpl getLocal()
    {
        return (LocalEndpointImpl) super.getLocal();
    }

    public void setFuture(CompletableFuture<Session> fut)
    {
        // TODO do we still need this?
    }

    @Override
    public void close()
    {
        super.close(WebSocketConstants.NORMAL, null, Callback.NOOP);
    }

    @Override
    public void close(CloseStatus closeStatus)
    {
        super.close(closeStatus, Callback.NOOP);
    }

    @Override
    public void close(int statusCode, String reason)
    {
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
    public RemoteEndpointImpl getRemote()
    {
        return (RemoteEndpointImpl) super.getRemote();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return getConnection().getRemoteAddress();
    }

    @Override
    public UpgradeRequest getUpgradeRequest()
    {
        return this.upgradeRequest;
    }

    @Override
    public UpgradeResponse getUpgradeResponse()
    {
        return this.upgradeResponse;
    }

    @Override
    public boolean isOpen()
    {
        return getSessionState().isOpen();
    }

    @Override
    public boolean isSecure()
    {
        // TODO: get "is secure" from HttpServletRequest? or Connection? or Jetty EndPoint?
        String scheme = getUpgradeRequest().getRequestURI().getScheme();
        return "wss".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    @Override
    public void onOpen()
    {
        // TODO: [EVENT] before onOpen
        super.onOpen();
        // TODO: [EVENT] after onOpen (potentially successful, how do we determine if in error?)
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
