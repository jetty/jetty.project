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

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link ConnectionFactory} for QUIC that can be used by
 * {@link Connector}s that are not {@link QuicServerConnector}.</p>
 */
public class QuicServerConnectionFactory extends AbstractConnectionFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicServerConnectionFactory.class);

    private final ServerQuicConfiguration quicConfiguration;

    public QuicServerConnectionFactory(ServerQuicConfiguration quicConfiguration)
    {
        super("quic");
        this.quicConfiguration = quicConfiguration;
    }

    public ServerQuicConfiguration getQuicConfiguration()
    {
        return quicConfiguration;
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
        addBean(quicConfiguration);
        super.doStart();
    }

    @Override
    public ServerQuicConnection newConnection(Connector connector, EndPoint endPoint)
    {
        ServerQuicConnection connection = new ServerQuicConnection(connector, quicConfiguration, endPoint);
        connection = configure(connection, connector, endPoint);
        connection.setOutputBufferSize(quicConfiguration.getOutputBufferSize());
        connection.setUseInputDirectByteBuffers(quicConfiguration.isUseInputDirectByteBuffers());
        connection.setUseOutputDirectByteBuffers(quicConfiguration.isUseOutputDirectByteBuffers());
        return connection;
    }
}
