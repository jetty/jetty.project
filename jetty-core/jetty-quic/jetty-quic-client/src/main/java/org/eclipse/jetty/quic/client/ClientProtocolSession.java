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

package org.eclipse.jetty.quic.client;

import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.common.StreamType;
import org.eclipse.jetty.quic.common.internal.QuicErrorCode;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Client specific implementation of {@link ProtocolSession}.</p>
 */
public class ClientProtocolSession extends ProtocolSession
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientProtocolSession.class);

    private final Runnable producer = Invocable.from(Invocable.InvocationType.NON_BLOCKING, this::produce);
    private QuicStreamEndPoint endPoint;

    public ClientProtocolSession(ClientQuicSession session)
    {
        super(session);
    }

    @Override
    public ClientQuicSession getQuicSession()
    {
        return (ClientQuicSession)super.getQuicSession();
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        onStart();
    }

    protected void onStart()
    {
        // Create a single bidirectional, client-initiated,
        // QUIC stream that plays the role of the TCP stream.
        long streamId = getQuicSession().newStreamId(StreamType.CLIENT_BIDIRECTIONAL);
        endPoint = getOrCreateStreamEndPoint(streamId, this::openProtocolEndPoint);
    }

    @Override
    protected void doStop() throws Exception
    {
        onStop();
        super.doStop();
    }

    protected void onStop()
    {
        QuicStreamEndPoint endPoint = this.endPoint;
        if (endPoint != null)
            endPoint.closed(null);
        this.endPoint = null;
    }

    @Override
    public Runnable getProducerTask()
    {
        // On the client, the contract is that applications should not block inside API callback methods,
        // so a call to produce() should never block, and we can return a NON_BLOCKING producer task.
        return producer;
    }

    @Override
    protected boolean onReadable(long readableStreamId)
    {
        // On the client, we need a get-only semantic in case of reads.
        QuicStreamEndPoint streamEndPoint = getStreamEndPoint(readableStreamId);
        if (LOG.isDebugEnabled())
            LOG.debug("stream #{} selected for read: {}", readableStreamId, streamEndPoint);
        if (streamEndPoint != null)
            return streamEndPoint.onReadable();
        return false;
    }

    @Override
    protected void onFailure(long error, String reason, Throwable failure)
    {
        outwardClose(QuicErrorCode.NO_ERROR.code(), "failure");
    }

    @Override
    protected void onClose(long error, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("session closed remotely 0x{}/{} {}", Long.toHexString(error), reason, this);
        // TODO: should probably close the stream.
    }
}
