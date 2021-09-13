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
import org.eclipse.jetty.http3.internal.HTTP3Connection;
import org.eclipse.jetty.http3.internal.parser.MessageParser;
import org.eclipse.jetty.http3.server.internal.ServerHTTP3Session;
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
    private int maxBlockedStreams;

    public AbstractHTTP3ServerConnectionFactory(HttpConfiguration httpConfiguration, Session.Server.Listener listener)
    {
        super("h3");
        this.httpConfiguration = Objects.requireNonNull(httpConfiguration);
        addBean(httpConfiguration);
        this.listener = listener;
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

    public int getMaxBlockedStreams()
    {
        return maxBlockedStreams;
    }

    public void setMaxBlockedStreams(int maxBlockedStreams)
    {
        this.maxBlockedStreams = maxBlockedStreams;
    }

    @Override
    public ProtocolSession newProtocolSession(QuicSession quicSession, Map<String, Object> context)
    {
        return new ServerHTTP3Session((ServerQuicSession)quicSession, listener, getMaxBlockedStreams(), getHttpConfiguration().getRequestHeaderSize());
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        // TODO: can the downcasts be removed?
        QuicStreamEndPoint streamEndPoint = (QuicStreamEndPoint)endPoint;
        long streamId = streamEndPoint.getStreamId();
        ServerHTTP3Session http3Session = (ServerHTTP3Session)streamEndPoint.getQuicSession().getProtocolSession();
        MessageParser parser = new MessageParser(streamId, http3Session.getQpackDecoder(), http3Session.getSessionServer());
        HTTP3Connection connection = new HTTP3Connection(endPoint, connector.getExecutor(), connector.getByteBufferPool(), parser);
        return connection;
    }
}
