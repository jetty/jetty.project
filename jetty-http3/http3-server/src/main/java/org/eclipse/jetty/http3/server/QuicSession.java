//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.server;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.http3.quiche.QuicheConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicSession
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicSession.class);

    private final QuicheConnection quicheConnection;
    private final ConcurrentMap<Long, QuicStreamEndPoint> endpoints = new ConcurrentHashMap<>();

    public QuicSession(QuicheConnection quicheConnection)
    {
        this.quicheConnection = quicheConnection;
    }

    public QuicheConnection getQuicheConnection()
    {
        return quicheConnection;
    }

    public QuicStreamEndPoint getOrCreateStreamEndPoint(Connector connector, Scheduler scheduler, QuicConnection quicConnection, InetSocketAddress localAddress, InetSocketAddress remoteAddress, long streamId)
    {
        QuicStreamEndPoint endPoint = endpoints.compute(streamId, (sid, quicStreamEndPoint) ->
        {
            if (quicStreamEndPoint == null)
            {
                quicStreamEndPoint = createQuicStreamEndPoint(connector, scheduler, quicConnection, localAddress, remoteAddress, streamId);
                LOG.debug("creating endpoint for stream {}", sid);
            }
            return quicStreamEndPoint;
        });
        LOG.debug("returning endpoint for stream {}", streamId);
        return endPoint;
    }

    private QuicStreamEndPoint createQuicStreamEndPoint(Connector connector, Scheduler scheduler, QuicConnection quicConnection, InetSocketAddress localAddress, InetSocketAddress remoteAddress, long streamId)
    {
        QuicStreamEndPoint endPoint = new QuicStreamEndPoint(scheduler, quicheConnection, quicConnection, localAddress, remoteAddress, streamId);

//        String negotiatedProtocol = quicheConnection.getNegotiatedProtocol();
//        ConnectionFactory connectionFactory = connector.getConnectionFactory(negotiatedProtocol);
//        if (connectionFactory == null)
//            throw new RuntimeException("No configured connection factory can handle protocol '" + negotiatedProtocol + "'");
        // TODO: The line below is to handle HTTP/0.9. Replace it with the commented lines above.
        ConnectionFactory connectionFactory = connector.getDefaultConnectionFactory();

        Connection connection = connectionFactory.newConnection(connector, endPoint);
        endPoint.setConnection(connection);
        endPoint.onOpen();
        connection.onOpen();
        return endPoint;
    }
}
