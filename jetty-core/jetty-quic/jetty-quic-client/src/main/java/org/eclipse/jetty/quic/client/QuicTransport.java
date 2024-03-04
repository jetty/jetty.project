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
import java.util.Objects;

import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.common.QuicConfiguration;

/**
 * <p>A {@link Transport} for QUIC that delegates to another {@code Transport}.</p>
 * <p>By default, the delegate is {@link Transport#UDP_IP}, but it may be a different
 * implementation.</p>
 */
public class QuicTransport extends Transport.Wrapper
{
    private final ClientQuicConfiguration quicConfiguration;

    public QuicTransport(ClientQuicConfiguration quicConfiguration)
    {
        this(UDP_IP, quicConfiguration);
    }

    public QuicTransport(Transport wrapped, ClientQuicConfiguration quicConfiguration)
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
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        context.put(QuicConfiguration.CONTEXT_KEY, quicConfiguration);
        ClientConnector clientConnector = (ClientConnector)context.get(ClientConnector.CLIENT_CONNECTOR_CONTEXT_KEY);
        ClientQuicConnection connection = new ClientQuicConnection(clientConnector, endPoint, context);
        connection.setInputBufferSize(quicConfiguration.getInputBufferSize());
        connection.setOutputBufferSize(quicConfiguration.getOutputBufferSize());
        connection.setUseInputDirectByteBuffers(quicConfiguration.isUseInputDirectByteBuffers());
        connection.setUseOutputDirectByteBuffers(quicConfiguration.isUseOutputDirectByteBuffers());
        quicConfiguration.getEventListeners().forEach(connection::addEventListener);
        return connection;
    }

    @Override
    public int hashCode()
    {
        return getWrapped().hashCode();
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj instanceof QuicTransport that)
            return Objects.equals(getWrapped(), that.getWrapped());
        return false;
    }
}
