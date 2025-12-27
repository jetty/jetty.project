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

package org.eclipse.jetty.quic.client;

import java.io.IOException;
import java.util.Map;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.client.internal.QuicClientConnectionFactory;

public class QuicTransport extends Transport.Wrapper
{
    private final QuicClientQuicConfiguration quicConfiguration;

    public QuicTransport(QuicClientQuicConfiguration quicConfiguration)
    {
        this(UDP_IP, quicConfiguration);
    }

    public QuicTransport(Transport wrapped, QuicClientQuicConfiguration quicConfiguration)
    {
        super(wrapped);
        this.quicConfiguration = quicConfiguration;
    }

    @Override
    public boolean isIntrinsicallySecure()
    {
        return true;
    }

    @Override
    public ClientConnectionFactory newClientConnectionFactory(ClientConnector connector, ClientConnectionFactory factory)
    {
        factory = super.newClientConnectionFactory(connector, factory);
        return new QuicClientConnectionFactory(factory, quicConfiguration);
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        return super.newConnection(endPoint, context);
    }
}
