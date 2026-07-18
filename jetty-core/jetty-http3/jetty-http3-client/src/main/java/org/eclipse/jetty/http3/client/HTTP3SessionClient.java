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

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.HTTP3Exception;
import org.eclipse.jetty.http3.HTTP3Session;
import org.eclipse.jetty.http3.HTTP3Stream;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.internal.ClientHTTP3Session;
import org.eclipse.jetty.http3.client.internal.HTTP3StreamClient;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
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

    private final Promise.Invocable<Client> promise;

    public HTTP3SessionClient(Scheduler scheduler, ProtocolSession session, Client.Listener listener, Promise.Invocable<Client> promise)
    {
        super(scheduler, session, listener);
        if (!(session instanceof ClientHTTP3Session))
            throw new IllegalArgumentException("Invalid session: " + session);
        this.promise = promise;
    }

    private ClientHTTP3Session getClientHTTP3Session()
    {
        return (ClientHTTP3Session)getProtocolSession();
    }

    public QpackEncoder getQpackEncoder()
    {
        return getClientHTTP3Session().getQpackEncoder();
    }

    public QpackDecoder getQpackDecoder()
    {
        return getClientHTTP3Session().getQpackDecoder();
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        if (promise != null)
            promise.succeeded(this);
    }

    @Override
    protected HTTP3Stream newHTTP3Stream(StreamEndPoint endPoint, boolean local)
    {
        return new HTTP3StreamClient(this, endPoint, local);
    }

    @Override
    protected void onHeaders(long streamId, HeadersFrame frame, boolean wasBlocked)
    {
        if (frame.getMetaData().isResponse())
        {
            StreamEndPoint endPoint = getProtocolSession().getStreamEndPoint(streamId);
            HTTP3StreamClient stream = (HTTP3StreamClient)getStream(endPoint.getStream().getId());
            if (LOG.isDebugEnabled())
                LOG.debug("received response {} on {}", frame, stream);
            if (stream != null)
                stream.onResponse(frame);
            else
                throw new HTTP3Exception.StreamException(HTTP3ErrorCode.REQUEST_REJECTED_ERROR, "missing_http3_stream");
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
        getClientHTTP3Session().onSettings(frame);
        super.onSettings(frame);
    }

    @Override
    public void newRequest(HeadersFrame frame, Stream.Client.Listener listener, Promise.Invocable<Stream> promise)
    {
        ClientHTTP3Session protocolSession = getClientHTTP3Session();
        var quicSession = protocolSession.getSession();
        long streamId = quicSession.newStreamId(true);
        AtomicReference<StreamEndPoint> endPointRef = new AtomicReference<>();
        var quicStream = quicSession.newStream(streamId, new ProtocolStreamListener.Local(endPointRef::get));
        StreamEndPoint endPoint = protocolSession.createStreamEndPoint(quicStream, protocolSession::openStreamEndPoint);
        endPointRef.set(endPoint);

        if (LOG.isDebugEnabled())
            LOG.debug("new request {} with {} on {}", quicStream, frame, this);

        HTTP3StreamClient stream;
        try
        {
            stream = (HTTP3StreamClient)createStream(endPoint);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("could not create stream for {} on {}", endPoint, this, x);
            endPoint.disconnect(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x, true, Callback.from(promise.getInvocationType(), t -> promise.failed(x)));
            return;
        }

        stream.setListener(listener);
        stream.onOpen();

        stream.writeFrame(frame, new Promise.Invocable.Abstract<>(promise.getInvocationType())
        {
            @Override
            public void succeeded(Stream result)
            {
                stream.updateClose(frame.isLast(), true);
                promise.succeeded(result);
            }

            @Override
            public void failed(Throwable x)
            {
                stream.updateClose(frame.isLast(), true);
                Invocable<Stream> p = Invocable.from(getInvocationType(), s -> promise.failed(x), t -> promise.failed(x));
                stream.disconnect(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x, p);
            }
        });
    }

    @Override
    public void writeControlFrame(Frame frame, Callback callback)
    {
        getClientHTTP3Session().writeControlFrame(frame, callback);
    }

    @Override
    public void writeMessageFrame(StreamEndPoint streamEndPoint, Frame frame, Callback callback)
    {
        getClientHTTP3Session().writeMessageFrame(streamEndPoint, frame, callback);
    }

    @Override
    protected GoAwayFrame newGoAwayFrame(boolean graceful)
    {
        if (graceful)
            return GoAwayFrame.CLIENT_GRACEFUL;
        return super.newGoAwayFrame(graceful);
    }
}
