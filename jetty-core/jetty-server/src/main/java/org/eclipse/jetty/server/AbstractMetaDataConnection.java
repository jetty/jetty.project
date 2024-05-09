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

package org.eclipse.jetty.server;

import java.net.SocketAddress;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;

/**
 * An {@link AbstractConnection} that also implements {@link ConnectionMetaData} with fixed
 * local and remote addresses.
 */
public abstract class AbstractMetaDataConnection extends AbstractConnection implements ConnectionMetaData
{
    private final Connector _connector;
    private final HttpConfiguration _httpConfiguration;
    private final SocketAddress _localSocketAddress;
    private final SocketAddress _remoteSocketAddress;

    public AbstractMetaDataConnection(Connector connector, HttpConfiguration httpConfiguration, EndPoint endPoint)
    {
        super(endPoint, connector.getExecutor());
        _connector = connector;
        _httpConfiguration = httpConfiguration;
        _localSocketAddress = httpConfiguration.getLocalAddress() != null ? httpConfiguration.getLocalAddress() : endPoint.getLocalSocketAddress();
        _remoteSocketAddress = endPoint.getRemoteSocketAddress();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return _remoteSocketAddress;
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return _localSocketAddress;
    }

    @Override
    public HttpConfiguration getHttpConfiguration()
    {
        return _httpConfiguration;
    }

    @Override
    public Connection getConnection()
    {
        return this;
    }

    @Override
    public Connector getConnector()
    {
        return _connector;
    }
}
