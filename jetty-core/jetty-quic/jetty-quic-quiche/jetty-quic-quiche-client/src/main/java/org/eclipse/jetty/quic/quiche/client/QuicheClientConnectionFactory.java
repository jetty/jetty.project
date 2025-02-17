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

package org.eclipse.jetty.quic.quiche.client;

import java.io.IOException;
import java.util.Map;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.quic.quiche.client.internal.ClientQuicheConnection;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class QuicheClientConnectionFactory extends ClientConnectionFactory.Wrapper
{
    public QuicheClientConnectionFactory(ClientConnectionFactory connectionFactory)
    {
        super(connectionFactory);
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        ClientConnector clientConnector = (ClientConnector)context.get(ClientConnector.CONTEXT_KEY);

        SslContextFactory.Client sslContextFactory = (SslContextFactory.Client)context.get(ClientConnector.SSL_CONTEXT_FACTORY_CONTEXT_KEY);
        // Support cases such as clear-text HTTP/1/2 over QUIC, where the SslContextFactory
        // is not present in the context (e.g. the scheme is "http"), but QUIC requires it.
        if (sslContextFactory == null)
            sslContextFactory = clientConnector.getSslContextFactory();

        QuicheClientQuicConfiguration quicConfiguration = (QuicheClientQuicConfiguration)context.get(ClientQuicConfiguration.CONTEXT_KEY);

        ClientQuicheConnection connection = new ClientQuicheConnection(clientConnector, sslContextFactory, quicConfiguration, getWrapped(), endPoint, context);
        return customize(connection, context);
    }
}
