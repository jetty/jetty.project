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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.CloseStatus;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;

public class WebSocketSessionImpl implements Session
{
    private final WebSocketContainerContext container;
    private final Behavior behavior;
    private final WebSocketPolicy sessionPolicy;
    private final JettyWebSocketRemoteEndpoint remoteEndpoint;
    private final HandshakeRequest handshakeRequest;
    private final HandshakeResponse handshakeResponse;

    public WebSocketSessionImpl(WebSocketContainerContext container,
                                Behavior behavior,
                                WebSocketPolicy sessionPolicy,
                                JettyWebSocketRemoteEndpoint remoteEndpoint,
                                HandshakeRequest handshakeRequest,
                                HandshakeResponse handshakeResponse)
    {
        this.container = container;
        this.behavior = behavior;
        this.sessionPolicy = sessionPolicy;
        this.remoteEndpoint = remoteEndpoint;
        this.handshakeRequest = handshakeRequest;
        this.handshakeResponse = handshakeResponse;
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
        return remoteEndpoint.getCoreSession().getIdleTimeout(TimeUnit.MILLISECONDS);
    }

    @Override
    public org.eclipse.jetty.websocket.api.WebSocketPolicy getPolicy()
    {
        org.eclipse.jetty.websocket.api.WebSocketPolicy policy =
        new org.eclipse.jetty.websocket.api.WebSocketPolicy(behavior==Behavior.SERVER?WebSocketBehavior.SERVER:WebSocketBehavior.CLIENT);

        policy.setInputBufferSize(sessionPolicy.getInputBufferSize());
        policy.setOutputBufferSize(sessionPolicy.getOutputBufferSize());
        policy.setIdleTimeout(sessionPolicy.getIdleTimeout());
        policy.setAsyncWriteTimeout(sessionPolicy.getAsyncWriteTimeout());
        policy.setMaxBinaryMessageSize(sessionPolicy.getMaxBinaryMessageSize());
        policy.setMaxBinaryMessageBufferSize(sessionPolicy.getMaxBinaryMessageBufferSize());
        policy.setMaxTextMessageSize(sessionPolicy.getMaxTextMessageSize());
        policy.setMaxTextMessageBufferSize(sessionPolicy.getMaxTextMessageBufferSize());
        policy.setMaxAllowedFrameSize(sessionPolicy.getMaxAllowedFrameSize());

        return policy;
    }

    @Override
    public String getProtocolVersion()
    {
        return handshakeRequest.getProtocolVersion();
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
        return handshakeRequest.isSecure();
    }

    @Override
    public void setIdleTimeout(long ms)
    {
        remoteEndpoint.getCoreSession().setIdleTimeout(ms, TimeUnit.MILLISECONDS);
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
        return String.format("WebSocketSessionImpl[%s,to=%,d,%s]", behavior, sessionPolicy.getIdleTimeout(), remoteEndpoint.getCoreSession());
    }
}
