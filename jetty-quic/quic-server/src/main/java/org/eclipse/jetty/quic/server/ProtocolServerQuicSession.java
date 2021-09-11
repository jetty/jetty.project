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

package org.eclipse.jetty.quic.server;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.quic.common.ProtocolQuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProtocolServerQuicSession extends ProtocolQuicSession
{
    private static final Logger LOG = LoggerFactory.getLogger(ProtocolServerQuicSession.class);

    public ProtocolServerQuicSession(ServerQuicSession session)
    {
        super(session);
    }

    @Override
    public ServerQuicSession getQuicSession()
    {
        return (ServerQuicSession)super.getQuicSession();
    }

    @Override
    public void onOpen()
    {
        process();
    }

    @Override
    protected void onReadable(long readableStreamId)
    {
        // On the server, we need a get-or-create semantic in case of reads.
        QuicStreamEndPoint streamEndPoint = getOrCreateStreamEndPoint(readableStreamId, this::configureEndPoint);
        if (LOG.isDebugEnabled())
            LOG.debug("stream {} selected endpoint for read: {}", readableStreamId, streamEndPoint);
        streamEndPoint.onReadable();
    }

    private void configureEndPoint(QuicStreamEndPoint endPoint)
    {
        Connection connection = getQuicSession().newConnection(endPoint);
        endPoint.setConnection(connection);
        endPoint.onOpen();
        connection.onOpen();
    }
}
