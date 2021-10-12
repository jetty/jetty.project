//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.server;

import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.internal.parser.MessageParser;
import org.eclipse.jetty.http3.server.internal.ServerHTTP3Session;
import org.eclipse.jetty.http3.server.internal.ServerHTTP3StreamConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.server.ServerQuicSession;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.annotation.ManagedAttribute;

public abstract class AbstractHTTP3ServerConnectionFactory extends AbstractConnectionFactory implements ProtocolSession.Factory
{
    private final HttpConfiguration httpConfiguration;
    private final Session.Server.Listener listener;
    private boolean useInputDirectByteBuffers = true;
    private boolean useOutputDirectByteBuffers = true;
    private int maxBlockedStreams = 0;
    private long streamIdleTimeout = 30000;

    public AbstractHTTP3ServerConnectionFactory(HttpConfiguration httpConfiguration, Session.Server.Listener listener)
    {
        super("h3");
        this.httpConfiguration = Objects.requireNonNull(httpConfiguration);
        addBean(httpConfiguration);
        this.listener = listener;
    }

    protected Session.Server.Listener getListener()
    {
        return listener;
    }

    @ManagedAttribute("Whether to use direct ByteBuffers for reading")
    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    @ManagedAttribute("Whether to use direct ByteBuffers for writing")
    public boolean isUseOutputDirectByteBuffers()
    {
        return useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return httpConfiguration;
    }

    @ManagedAttribute("The max number of streams blocked in QPACK encoding")
    public int getMaxBlockedStreams()
    {
        return maxBlockedStreams;
    }

    public void setMaxBlockedStreams(int maxBlockedStreams)
    {
        this.maxBlockedStreams = maxBlockedStreams;
    }

    @ManagedAttribute("The stream idle timeout in milliseconds")
    public long getStreamIdleTimeout()
    {
        return streamIdleTimeout;
    }

    public void setStreamIdleTimeout(long streamIdleTimeout)
    {
        this.streamIdleTimeout = streamIdleTimeout;
    }

    @Override
    public ProtocolSession newProtocolSession(QuicSession quicSession, Map<String, Object> context)
    {
        ServerHTTP3Session session = new ServerHTTP3Session((ServerQuicSession)quicSession, listener, getMaxBlockedStreams(), getHttpConfiguration().getRequestHeaderSize());
        session.setStreamIdleTimeout(getStreamIdleTimeout());
        return session;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        QuicStreamEndPoint streamEndPoint = (QuicStreamEndPoint)endPoint;
        long streamId = streamEndPoint.getStreamId();
        ServerHTTP3Session http3Session = (ServerHTTP3Session)streamEndPoint.getQuicSession().getProtocolSession();
        MessageParser parser = new MessageParser(http3Session.getSessionServer(), http3Session.getQpackDecoder(), streamId, streamEndPoint::isStreamFinished);
        return new ServerHTTP3StreamConnection(connector, getHttpConfiguration(), streamEndPoint, http3Session, parser);
    }
}
