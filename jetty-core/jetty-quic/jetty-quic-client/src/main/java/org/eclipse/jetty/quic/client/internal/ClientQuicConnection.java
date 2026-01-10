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

package org.eclipse.jetty.quic.client.internal;

import java.util.Map;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.quic.common.QuicConnection;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.tls.ClientQuicTLS;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class ClientQuicConnection extends QuicConnection implements Promise.Invocable<Session>
{
    private ClientConnector connector;
    private SslContextFactory.Client sslContextFactory;
    private QuicClientQuicConfiguration quicConfiguration;
    private ClientConnectionFactory clientConnectionFactory;
    private Map<String, Object> context;
    private ClientQuicSession session;

    public ClientQuicConnection(ClientConnector connector, SslContextFactory.Client sslContextFactory, QuicClientQuicConfiguration quicConfiguration, ClientConnectionFactory clientConnectionFactory, EndPoint endPoint, Map<String, Object> context)
    {
        super(connector.getByteBufferPool(), connector.getExecutor(), endPoint);
        this.connector = connector;
        this.sslContextFactory = sslContextFactory;
        this.quicConfiguration = quicConfiguration;
        this.clientConnectionFactory = clientConnectionFactory;
        this.context = context;
    }

    @Override
    public void onOpen()
    {
        PacketNumbers packetNumbers = new PacketNumbers();
        ClientQuicTLS clientQuicTLS = new ClientQuicTLS(connector.getByteBufferPool(), packetNumbers);
        session = new ClientQuicSession(connector, quicConfiguration, packetNumbers, clientQuicTLS, getEndPoint(), context);
        session.connect(this);
    }

    @Override
    public void succeeded(Session result)
    {
        super.onOpen();
        fillInterested();
    }

    @Override
    public void failed(Throwable x)
    {
        // TODO
    }

    @Override
    protected void process(RetainableByteBuffer buffer) throws Exception
    {
        session.process(buffer);
    }
}
