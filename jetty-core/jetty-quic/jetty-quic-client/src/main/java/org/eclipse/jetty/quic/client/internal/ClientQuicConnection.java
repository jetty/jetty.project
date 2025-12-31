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
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class ClientQuicConnection extends AbstractConnection
{
    private ClientConnector connector;
    private SslContextFactory.Client sslContextFactory;
    private QuicClientQuicConfiguration quicConfiguration;
    private ClientConnectionFactory clientConnectionFactory;
    private Map<String, Object> context;

    public ClientQuicConnection(EndPoint endPoint, Executor executor)
    {
        super(endPoint, executor);
    }

    public ClientQuicConnection(ClientConnector connector, SslContextFactory.Client sslContextFactory, QuicClientQuicConfiguration quicConfiguration, ClientConnectionFactory clientConnectionFactory, EndPoint endPoint, Map<String, Object> context)
    {
        super(endPoint, connector.getExecutor());
        this.connector = connector;
        this.sslContextFactory = sslContextFactory;
        this.quicConfiguration = quicConfiguration;
        this.clientConnectionFactory = clientConnectionFactory;
        this.context = context;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();

        // TODO: ALPN not strictly necessary for QUIC?
//        @SuppressWarnings("unchecked")
//        List<String> protocols = (List<String>)context.get(ClientConnector.APPLICATION_PROTOCOLS_CONTEXT_KEY);
//        if (protocols == null || protocols.isEmpty())
//            throw new IllegalStateException("missing ALPN protocols");

    }

    @Override
    public void onFillable()
    {
    }
}
