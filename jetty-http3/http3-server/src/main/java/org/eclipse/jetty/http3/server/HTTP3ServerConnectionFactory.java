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

import java.util.Objects;

import org.eclipse.jetty.http3.internal.HTTP3Connection;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.http3.internal.parser.Parser;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.common.ProtocolQuicSession;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;

public class HTTP3ServerConnectionFactory extends AbstractConnectionFactory implements ProtocolQuicSession.Factory
{
    private final HttpConfiguration httpConfiguration;

    public HTTP3ServerConnectionFactory()
    {
        this(new HttpConfiguration());
    }

    public HTTP3ServerConnectionFactory(HttpConfiguration configuration)
    {
        super("h3");
        this.httpConfiguration = Objects.requireNonNull(configuration);
        addBean(httpConfiguration);
    }

    @Override
    public ProtocolQuicSession newProtocolQuicSession(QuicSession quicSession)
    {
        return new HTTP3QuicSession(quicSession, httpConfiguration.getResponseHeaderSize());
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        // TODO: can the downcasts be removed?
        long streamId = ((QuicStreamEndPoint)endPoint).getStreamId();
        HTTP3QuicSession http3QuicSession = (HTTP3QuicSession)((QuicStreamEndPoint)endPoint).getQuicSession().getProtocolQuicSession();

        HTTP3Session session = new HTTP3Session();

        Parser parser = new Parser(streamId, session, http3QuicSession.getQpackDecoder());

        HTTP3Connection connection = new HTTP3Connection(endPoint, connector.getExecutor(), parser);
        return connection;
    }
}
