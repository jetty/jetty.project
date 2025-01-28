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

package org.eclipse.jetty.http3.client;

import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.HTTP3Session;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.internal.ClientHTTP3Session;
import org.eclipse.jetty.http3.client.internal.HTTP3StreamClient;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.quic.common.AbstractStream;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.ProtocolStreamListener;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3SessionClient extends HTTP3Session implements Session.Client
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3SessionClient.class);

    private final Promise<Client> promise;

    public HTTP3SessionClient(Scheduler scheduler, ClientHTTP3Session session, Client.Listener listener, Promise<Client> promise)
    {
        super(scheduler, session, listener);
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
    protected HTTP3StreamClient newHTTP3Stream(StreamEndPoint endPoint, boolean local)
    {
        return new HTTP3StreamClient(this, endPoint, local);
    }

    @Override
    public void onHeaders(long streamId, HeadersFrame frame, boolean wasBlocked)
    {
        if (frame.getMetaData().isResponse())
        {
            StreamEndPoint endPoint = getProtocolSession().getStreamEndPoint(streamId);
            HTTP3StreamClient stream = (HTTP3StreamClient)getOrCreateStream(endPoint);
            if (LOG.isDebugEnabled())
                LOG.debug("received response {} on {}", frame, stream);
            if (stream != null)
                stream.onResponse(frame);
        }
        else
        {
            super.onHeaders(streamId, frame, wasBlocked);
        }
    }

    @Override
    public void onSettings(SettingsFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("received {} on {}", frame, this);
        getProtocolSession().onSettings(frame);
        super.onSettings(frame);
    }

    @Override
    public CompletableFuture<Stream> newRequest(HeadersFrame frame, Stream.Client.Listener listener)
    {
        var quicSession = getProtocolSession().getSession();
        long streamId = quicSession.newStreamId(true);
        var quicStream = quicSession.newStream(streamId, null);

        if (LOG.isDebugEnabled())
            LOG.debug("new request {} with {} on {}", quicStream, frame, this);

        ProtocolSession session = getProtocolSession();
        StreamEndPoint endPoint = session.getOrCreateStreamEndPoint(quicStream, session::openStreamEndPoint);
        ((AbstractStream)quicStream).setListener(new ProtocolStreamListener(() -> endPoint));

        Promise.Completable<Stream> promise = new Promise.Completable<>();
        promise.whenComplete((s, x) ->
        {
            if (x != null)
                endPoint.disconnect(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x, true);
        });
        HTTP3StreamClient stream = (HTTP3StreamClient)createStream(endPoint, promise::failed);
        if (stream == null)
            return promise;

        stream.setListener(listener);
        stream.onOpen();

        stream.writeFrame(frame)
            .whenComplete((r, x) ->
            {
                stream.updateClose(frame.isLast(), true);
                if (x == null)
                {
                    promise.succeeded(stream);
                }
                else
                {
                    stream.disconnect(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x)
                        .whenComplete((s, t) -> promise.failed(x));
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
    public void writeMessageFrame(StreamEndPoint streamEndPoint, Frame frame, Callback callback)
    {
        getProtocolSession().writeMessageFrame(streamEndPoint, frame, callback);
    }

    @Override
    protected GoAwayFrame newGoAwayFrame(boolean graceful)
    {
        if (graceful)
            return GoAwayFrame.CLIENT_GRACEFUL;
        return super.newGoAwayFrame(graceful);
    }
}
