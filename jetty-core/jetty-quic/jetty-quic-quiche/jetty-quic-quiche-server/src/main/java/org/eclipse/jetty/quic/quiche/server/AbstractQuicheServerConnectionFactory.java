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

package org.eclipse.jetty.quic.quiche.server;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.quiche.server.internal.ServerQuicheConnection;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Base class for QUIC {@link ConnectionFactory}s that uses the Quiche library.</p>
 */
public abstract class AbstractQuicheServerConnectionFactory extends AbstractConnectionFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractQuicheServerConnectionFactory.class);

    private final SslContextFactory.Server sslContextFactory;
    private final QuicheServerQuicConfiguration quicConfiguration;
    private final Session.Listener.Factory sessionListenerFactory;

    public AbstractQuicheServerConnectionFactory(SslContextFactory.Server sslContextFactory, QuicheServerQuicConfiguration quicConfiguration, Session.Listener.Factory sessionListenerFactory)
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

    public QuicheServerQuicConfiguration getServerQuicConfiguration()
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
        ServerQuicheConnection connection = new ServerQuicheConnection(connector, getSslContextFactory(), getServerQuicConfiguration(), endPoint, getSessionListenerFactory());
        return configure(connection, connector, endPoint);
    }
}
