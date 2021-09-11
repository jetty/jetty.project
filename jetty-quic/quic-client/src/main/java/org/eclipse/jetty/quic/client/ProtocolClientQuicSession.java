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

package org.eclipse.jetty.quic.client;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.quic.common.ProtocolQuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProtocolClientQuicSession extends ProtocolQuicSession
{
    private static final Logger LOG = LoggerFactory.getLogger(ProtocolClientQuicSession.class);

    public ProtocolClientQuicSession(ClientQuicSession session)
    {
        super(session);
    }

    @Override
    public ClientQuicSession getQuicSession()
    {
        return (ClientQuicSession)super.getQuicSession();
    }

    @Override
    public void onOpen()
    {
        // Create a single bidirectional, client-initiated,
        // QUIC stream that plays the role of the TCP stream.
        configureEndPoint(getQuicSession().newClientBidirectionalStreamId());
        process();
    }

    private void configureEndPoint(long streamId)
    {
        getOrCreateStreamEndPoint(streamId, endPoint ->
        {
            try
            {
                Connection connection = getQuicSession().newConnection(endPoint);
                endPoint.setConnection(connection);
                endPoint.onOpen();
                connection.onOpen();
            }
            catch (RuntimeException | Error x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("could not open protocol QUIC session", x);
                throw x;
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("could not open protocol QUIC session", x);
                throw new RuntimeException(x);
            }
        });
    }

    @Override
    protected void onReadable(long readableStreamId)
    {
        // On the client, we need a get-only semantic in case of reads.
        QuicStreamEndPoint streamEndPoint = getStreamEndPoint(readableStreamId);
        if (LOG.isDebugEnabled())
            LOG.debug("stream {} selected endpoint for read: {}", readableStreamId, streamEndPoint);
        if (streamEndPoint != null)
            streamEndPoint.onReadable();
    }
}
