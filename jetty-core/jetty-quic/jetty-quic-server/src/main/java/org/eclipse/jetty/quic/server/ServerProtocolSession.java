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

import java.io.IOException;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;

/**
 * <p>Server specific implementation of {@link ProtocolSession}.</p>
 */
public class ServerProtocolSession extends ProtocolSession
{
    private final Connector connector;
    private final ConnectionFactory connectionFactory;

    public ServerProtocolSession(Connector connector, Session session, ConnectionFactory connectionFactory)
    {
        super(connector.getExecutor(), connector.getByteBufferPool(), session);
        this.connector = connector;
        this.connectionFactory = connectionFactory;
    }

    @Override
    protected Connection newConnection(StreamEndPoint endPoint) throws IOException
    {
        return connectionFactory.newConnection(connector, endPoint);
    }
}
