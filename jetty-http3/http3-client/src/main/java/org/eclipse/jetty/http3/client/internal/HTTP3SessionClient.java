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

package org.eclipse.jetty.http3.client.internal;

import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.common.StreamType;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3SessionClient extends HTTP3Session implements Session.Client
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3SessionClient.class);

    private final Promise<Client> promise;

    public HTTP3SessionClient(ClientHTTP3Session session, Client.Listener listener, Promise<Client> promise)
    {
        super(session, listener);
        this.promise = promise;
    }

    @Override
    public ClientHTTP3Session getProtocolSession()
    {
        return (ClientHTTP3Session)super.getProtocolSession();
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        promise.succeeded(this);
    }

    @Override
    public void onHeaders(long streamId, HeadersFrame frame)
    {
        if (frame.getMetaData().isResponse())
        {
            QuicStreamEndPoint endPoint = getProtocolSession().getStreamEndPoint(streamId);
            HTTP3Stream stream = getOrCreateStream(endPoint);
            if (LOG.isDebugEnabled())
                LOG.debug("received response {} on {}", frame, stream);
            if (stream != null)
                stream.onResponse(frame);
        }
        else
        {
            super.onHeaders(streamId, frame);
        }
    }

    @Override
    public CompletableFuture<Stream> newRequest(HeadersFrame frame, Stream.Listener listener)
    {
        long streamId = getProtocolSession().getQuicSession().newStreamId(StreamType.CLIENT_BIDIRECTIONAL);
        return newRequest(streamId, frame, listener);
    }

    @Override
    public void writeControlFrame(Frame frame, Callback callback)
    {
        getProtocolSession().writeControlFrame(frame, callback);
    }

    @Override
    public void writeMessageFrame(long streamId, Frame frame, Callback callback)
    {
        getProtocolSession().writeMessageFrame(streamId, frame, callback);
    }

    @Override
    protected GoAwayFrame newGoAwayFrame(boolean graceful)
    {
        if (graceful)
            return GoAwayFrame.CLIENT_GRACEFUL;
        return super.newGoAwayFrame(graceful);
    }
}
