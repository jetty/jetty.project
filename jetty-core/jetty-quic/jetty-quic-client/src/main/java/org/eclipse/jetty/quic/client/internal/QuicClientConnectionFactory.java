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

import java.io.IOException;
import java.util.Map;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class QuicClientConnectionFactory extends ClientConnectionFactory.Wrapper
{
    private final QuicClientQuicConfiguration quicConfiguration;

    public QuicClientConnectionFactory(ClientConnectionFactory factory, QuicClientQuicConfiguration quicConfiguration)
    {
        super(factory);
        this.quicConfiguration = quicConfiguration;
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        ClientConnector clientConnector = (ClientConnector)context.get(ClientConnector.CONTEXT_KEY);
        SslContextFactory.Client sslContextFactory = (SslContextFactory.Client)context.get(ClientConnector.SSL_CONTEXT_FACTORY_CONTEXT_KEY);
        ClientQuicConnection connection = new ClientQuicConnection(clientConnector, sslContextFactory, quicConfiguration, getWrapped(), endPoint, context);

        // TODO: probably also setup parser, tlsEngine, etc.


        return customize(connection, context);
    }
}
