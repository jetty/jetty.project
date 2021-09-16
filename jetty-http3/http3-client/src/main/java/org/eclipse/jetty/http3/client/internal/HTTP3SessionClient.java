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
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.http3.internal.HTTP3Stream;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.common.StreamType;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Invocable;
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
    protected void writeFrame(long streamId, Frame frame, Callback callback)
    {
        getProtocolSession().writeFrame(streamId, frame, callback);
    }

    public void onOpen()
    {
        promise.succeeded(this);
    }

    @Override
    public CompletableFuture<Stream> newRequest(HeadersFrame frame, Stream.Listener listener)
    {
        ClientHTTP3Session session = getProtocolSession();
        long streamId = session.getQuicSession().newStreamId(StreamType.CLIENT_BIDIRECTIONAL);
        QuicStreamEndPoint streamEndPoint = session.getOrCreateStreamEndPoint(streamId, session::configureProtocolEndPoint);
        if (LOG.isDebugEnabled())
            LOG.debug("created request/response stream #{} on {}", streamId, streamEndPoint);

        Promise.Completable<Stream> promise = new Promise.Completable<>();
        HTTP3Stream stream = createStream(streamEndPoint);
        stream.setListener(listener);
        Callback callback = Callback.from(Invocable.InvocationType.NON_BLOCKING, () -> promise.succeeded(stream), promise::failed);

        session.writeFrame(streamId, frame, callback);
        return promise;
    }
}
