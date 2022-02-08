//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.util.function.Consumer;

import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Server specific implementation of {@link ProtocolSession}.</p>
 */
public class ServerProtocolSession extends ProtocolSession
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerProtocolSession.class);

    private final Runnable producer = Invocable.from(Invocable.InvocationType.EITHER, this::produce);
    private final Consumer<QuicStreamEndPoint> openProtocolEndPoint = this::openProtocolEndPoint;

    public ServerProtocolSession(ServerQuicSession session)
    {
        super(session);
    }

    @Override
    public ServerQuicSession getQuicSession()
    {
        return (ServerQuicSession)super.getQuicSession();
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        onStart();
    }

    protected void onStart()
    {
    }

    @Override
    protected void doStop() throws Exception
    {
        onStop();
        super.doStop();
    }

    protected void onStop()
    {
    }

    @Override
    public Runnable getProducerTask()
    {
        // On the server, a call to produce() may process a stream which then parses a request,
        // which then typically produces a blocking task that calls the application, which may
        // be run by the ExecutionStrategy and therefore block the current thread.
        // The producer task is always blocking to provide a "thread per active connection"
        // model similar to what happens on the server with TCP networking.
        return producer;
    }

    @Override
    protected boolean onReadable(long readableStreamId)
    {
        // On the server, we need a get-or-create semantic in case of reads.
        QuicStreamEndPoint streamEndPoint = getOrCreateStreamEndPoint(readableStreamId, openProtocolEndPoint);
        if (LOG.isDebugEnabled())
            LOG.debug("stream #{} selected for read: {}", readableStreamId, streamEndPoint);
        return streamEndPoint.onReadable();
    }

    @Override
    protected void onFailure(long error, String reason, Throwable failure)
    {
        // TODO: should probably reset the stream if it exists.
    }

    @Override
    protected void onClose(long error, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("session closed remotely 0x{}/{} {}", Long.toHexString(error), reason, this);
        // TODO: should probably reset the stream if it exists.
    }
}
