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

package org.eclipse.jetty.quic.server;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.server.internal.ServerQuicConnection;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Base class for QUIC [ConnectionFactory]s.
public abstract class AbstractQuicServerConnectionFactory extends AbstractConnectionFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractQuicServerConnectionFactory.class);

    private final SslContextFactory.Server sslContextFactory;
    private final QuicServerQuicConfiguration quicConfiguration;
    private final Session.Listener.Factory sessionListenerFactory;

    public AbstractQuicServerConnectionFactory(SslContextFactory.Server sslContextFactory, QuicServerQuicConfiguration quicConfiguration, Session.Listener.Factory sessionListenerFactory)
    {
        super("quic");
        this.sslContextFactory = sslContextFactory;
        this.quicConfiguration = quicConfiguration;
        this.sessionListenerFactory = sessionListenerFactory;
    }

    public SslContextFactory.Server getSslContextFactory()
    {
        return sslContextFactory;
    }

    public QuicServerQuicConfiguration getServerQuicConfiguration()
    {
        return quicConfiguration;
    }

    public Session.Listener.Factory getSessionListenerFactory()
    {
        return sessionListenerFactory;
    }

    @Override
    public int getInputBufferSize()
    {
        return quicConfiguration.getInputBufferSize();
    }

    @Override
    public void setInputBufferSize(int size)
    {
        quicConfiguration.setInputBufferSize(size);
    }

    @Override
    protected void doStart() throws Exception
    {
        LOG.info("HTTP/3+QUIC support is experimental and not suited for production use.");
        addBean(sslContextFactory);
        addBean(quicConfiguration);
        super.doStart();
        quicConfiguration.configure(sslContextFactory);
    }

    @Override
    protected void doStop() throws Exception
    {
        quicConfiguration.deconfigure(sslContextFactory);
        super.doStop();
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        ServerQuicConnection connection = new ServerQuicConnection(connector, getSslContextFactory(), getServerQuicConfiguration(), endPoint, getSessionListenerFactory());
        connection.setInputBufferSize(getInputBufferSize());
        connection.setUseInputDirectByteBuffers(getServerQuicConfiguration().isUseInputDirectByteBuffers());
        connection.setDestinationConnectionIdLength(getServerQuicConfiguration().getDestinationConnectionIdLength());
        return configure(connection, connector, endPoint);
    }
}
