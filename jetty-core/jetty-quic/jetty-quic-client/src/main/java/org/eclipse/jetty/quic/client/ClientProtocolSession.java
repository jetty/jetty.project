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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.ProtocolStreamListener;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Client specific implementation of {@link ProtocolSession}.</p>
 */
public class ClientProtocolSession extends ProtocolSession
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientProtocolSession.class);

    private final ClientConnectionFactory connectionFactory;
    private final Map<String, Object> context;

    public ClientProtocolSession(ClientConnector clientConnector, Session session, ClientConnectionFactory connectionFactory, Map<String, Object> context)
    {
        super(clientConnector.getExecutor(), clientConnector.getByteBufferPool(), session);
        this.connectionFactory = connectionFactory;
        this.context = context;
    }

    protected void onStart()
    {
        try
        {
            // Create a single bidirectional, client-initiated,
            // QUIC stream that plays the role of the TCP stream.
            long streamId = getSession().newStreamId(true);
            AtomicReference<StreamEndPoint> endPointRef = new AtomicReference<>();
            Stream stream = getSession().newStream(streamId, new ProtocolStreamListener.Client(endPointRef::get));
            endPointRef.set(createStreamEndPoint(stream, this::openStreamEndPoint));
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("could not create stream", x);
            ConnectionCloseFrame disconnect = new ConnectionCloseFrame(ErrorCode.INTERNAL_ERROR.code(), "start_failure");
            disconnect(disconnect, x, Callback.NOOP);
        }
    }

    @Override
    protected Connection newConnection(StreamEndPoint endPoint) throws IOException
    {
        return connectionFactory.newConnection(endPoint, context);
    }
}
