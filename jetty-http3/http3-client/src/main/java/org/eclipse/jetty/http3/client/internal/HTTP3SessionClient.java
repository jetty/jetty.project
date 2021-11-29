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
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.quic.common.ProtocolSession;
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
        if (promise != null)
            promise.succeeded(this);
    }

    @Override
    protected HTTP3StreamClient newHTTP3Stream(QuicStreamEndPoint endPoint, boolean local)
    {
        return new HTTP3StreamClient(this, endPoint, local);
    }

    @Override
    public void onHeaders(long streamId, HeadersFrame frame)
    {
        if (frame.getMetaData().isResponse())
        {
            QuicStreamEndPoint endPoint = getProtocolSession().getStreamEndPoint(streamId);
            HTTP3StreamClient stream = (HTTP3StreamClient)getOrCreateStream(endPoint);
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
    public CompletableFuture<Stream> newRequest(HeadersFrame frame, Stream.Client.Listener listener)
    {
        long streamId = getProtocolSession().getQuicSession().newStreamId(StreamType.CLIENT_BIDIRECTIONAL);
        return newRequest(streamId, frame, listener);
    }

    private CompletableFuture<Stream> newRequest(long streamId, HeadersFrame frame, Stream.Client.Listener listener)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("new request stream #{} with {} on {}", streamId, frame, this);

        ProtocolSession session = getProtocolSession();
        QuicStreamEndPoint endPoint = session.getOrCreateStreamEndPoint(streamId, session::openProtocolEndPoint);

        Promise.Completable<Stream> promise = new Promise.Completable<>();
        promise.whenComplete((s, x) ->
        {
            if (x != null)
                endPoint.close(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x);
        });
        HTTP3StreamClient stream = (HTTP3StreamClient)createStream(endPoint, promise::failed);
        if (stream == null)
            return promise;

        stream.setListener(listener);

        stream.writeFrame(frame)
            .whenComplete((r, x) ->
            {
                if (x == null)
                {
                    if (listener == null)
                        endPoint.shutdownInput(HTTP3ErrorCode.NO_ERROR.code());
                    stream.updateClose(frame.isLast(), true);
                    promise.succeeded(stream);
                }
                else
                {
                    removeStream(stream, x);
                    promise.failed(x);
                }
            });

        return promise;
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
