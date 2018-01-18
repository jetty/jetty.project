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
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.io.SuspendToken;

public class WebSocketSessionImpl implements Session
{
    private final JettyWebSocketRemoteEndpoint remoteEndpoint;
    private final HandshakeRequest handshakeRequest;
    private final HandshakeResponse handshakeResponse;

    public WebSocketSessionImpl(JettyWebSocketRemoteEndpoint remoteEndpoint,
                                HandshakeRequest handshakeRequest,
                                HandshakeResponse handshakeResponse)
    {
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
        remoteEndpoint.close(closeStatus.getCode(), closeStatus.getReason());
    }

    @Override
    public void close(int statusCode, String reason)
    {
        remoteEndpoint.close(statusCode, reason);
    }

    @Override
    public void close(StatusCode statusCode, String reason)
    {
        this.close(statusCode.getCode(), reason);
    }

    @Override
    public void abort() throws IOException
    {
        remoteEndpoint.getChannel().abort();
    }

    @Override
    public long getIdleTimeout()
    {
        return remoteEndpoint.getChannel().getIdleTimeout(TimeUnit.MILLISECONDS);
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return handshakeRequest.getLocalSocketAddress();
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return null;
    }

    @Override
    public String getProtocolVersion()
    {
        return getHandshakeRequest().getProtocolVersion();
    }

    @Override
    public JettyWebSocketRemoteEndpoint getRemote()
    {
        return remoteEndpoint;
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return handshakeRequest.getRemoteSocketAddress();
    }

    @Override
    public HandshakeRequest getHandshakeRequest()
    {
        return this.handshakeRequest;
    }

    @Override
    public HandshakeResponse getHandshakeResponse()
    {
        return this.handshakeResponse;
    }

    @Override
    public boolean isOpen()
    {
        return remoteEndpoint.getChannel().isOpen();
    }

    @Override
    public boolean isSecure()
    {
        return handshakeRequest.isSecure();
    }

    @Override
    public void setIdleTimeout(long ms)
    {
        remoteEndpoint.getChannel().setIdleTimeout(ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public SuspendToken suspend()
    {
        // TODO: need to implement
        throw new UnsupportedOperationException("Not supported in websocket-core yet");
    }
}
