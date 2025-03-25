//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http3.HTTP3Configuration;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.parser.MessageParser;
import org.eclipse.jetty.http3.server.internal.ServerHTTP3Session;
import org.eclipse.jetty.http3.server.internal.ServerHTTP3StreamConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.server.ServerQuicConfiguration;
import org.eclipse.jetty.quic.server.ServerQuicSession;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;

public abstract class AbstractHTTP3ServerConnectionFactory extends AbstractConnectionFactory implements ProtocolSession.Factory
{
    private final HTTP3Configuration http3Configuration = new HTTP3Configuration();
    private final ServerQuicConfiguration quicConfiguration;
    private final HttpConfiguration httpConfiguration;
    private final Session.Server.Listener listener;

    public AbstractHTTP3ServerConnectionFactory(ServerQuicConfiguration quicConfiguration, HttpConfiguration httpConfiguration, Session.Server.Listener listener)
    {
        super("h3");
        this.quicConfiguration = Objects.requireNonNull(quicConfiguration);
        this.httpConfiguration = Objects.requireNonNull(httpConfiguration);
        this.listener = listener;
        // Max concurrent streams that a client can open.
        quicConfiguration.setMaxBidirectionalRemoteStreams(128);
        // HTTP/3 requires a few mandatory unidirectional streams.
        quicConfiguration.setMaxUnidirectionalRemoteStreams(8);
        quicConfiguration.setUnidirectionalStreamRecvWindow(1024 * 1024);
        http3Configuration.setUseInputDirectByteBuffers(httpConfiguration.isUseInputDirectByteBuffers());
        http3Configuration.setUseOutputDirectByteBuffers(httpConfiguration.isUseOutputDirectByteBuffers());
        http3Configuration.setMaxRequestHeadersSize(httpConfiguration.getRequestHeaderSize());
        int maxResponseHeaderSize = httpConfiguration.getMaxResponseHeaderSize();
        if (maxResponseHeaderSize < 0)
            maxResponseHeaderSize = getHttpConfiguration().getResponseHeaderSize();
        http3Configuration.setMaxResponseHeadersSize(maxResponseHeaderSize);
    }

    public ServerQuicConfiguration getQuicConfiguration()
    {
        return quicConfiguration;
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return httpConfiguration;
    }

    public HTTP3Configuration getHTTP3Configuration()
    {
        return http3Configuration;
    }

    @Override
    protected void doStart() throws Exception
    {
        addBean(quicConfiguration);
        addBean(http3Configuration);
        addBean(httpConfiguration);
        super.doStart();
    }

    @Override
    public ProtocolSession newProtocolSession(QuicSession quicSession, Map<String, Object> context)
    {
        return new ServerHTTP3Session(getHTTP3Configuration(), (ServerQuicSession)quicSession, listener);
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        QuicStreamEndPoint streamEndPoint = (QuicStreamEndPoint)endPoint;
        long streamId = streamEndPoint.getStreamId();
        ServerHTTP3Session http3Session = (ServerHTTP3Session)streamEndPoint.getQuicSession().getProtocolSession();
        MessageParser parser = new MessageParser(http3Session.getSessionServer(), http3Session.getQpackDecoder(), streamId, streamEndPoint::isStreamFinished);
        ServerHTTP3StreamConnection connection = new ServerHTTP3StreamConnection(connector, getHttpConfiguration(), streamEndPoint, http3Session, parser);
        return configure(connection, connector, endPoint);
    }
}
