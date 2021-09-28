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

package org.eclipse.jetty.http3.internal;

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3Stream implements Stream
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3Stream.class);

    private final HTTP3Session session;
    private final QuicStreamEndPoint endPoint;
    private Listener listener;
    private FrameState frameState = FrameState.INITIAL;

    public HTTP3Stream(HTTP3Session session, QuicStreamEndPoint endPoint)
    {
        this.session = session;
        this.endPoint = endPoint;
    }

    @Override
    public long getId()
    {
        return endPoint.getStreamId();
    }

    @Override
    public Session getSession()
    {
        return session;
    }

    public Listener getListener()
    {
        return listener;
    }

    public void setListener(Listener listener)
    {
        this.listener = listener;
    }

    @Override
    public CompletableFuture<Stream> respond(HeadersFrame frame)
    {
        return writeFrame(frame);
    }

    @Override
    public CompletableFuture<Stream> data(DataFrame frame)
    {
        return writeFrame(frame);
    }

    @Override
    public Data readData()
    {
        HTTP3StreamConnection connection = (HTTP3StreamConnection)endPoint.getConnection();
        return connection.readData();
    }

    @Override
    public void demand()
    {
        HTTP3StreamConnection connection = (HTTP3StreamConnection)endPoint.getConnection();
        connection.demand();
    }

    @Override
    public CompletableFuture<Stream> trailer(HeadersFrame frame)
    {
        if (!frame.isLast())
            throw new IllegalArgumentException("invalid trailer frame: property 'last' must be true");
        return writeFrame(frame);
    }

    public boolean hasDemand()
    {
        HTTP3StreamConnection connection = (HTTP3StreamConnection)endPoint.getConnection();
        return connection.hasDemand();
    }

    public void processRequest(HeadersFrame frame)
    {
        if (validateAndUpdate(EnumSet.of(FrameState.INITIAL), FrameState.HEADER))
        {
            Stream.Listener streamListener = notifyRequest(frame);
            setListener(streamListener);
        }
    }

    private Stream.Listener notifyRequest(HeadersFrame frame)
    {
        Session.Listener listener = session.getListener();
        try
        {
            return listener.onRequest(this, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
            return null;
        }
    }

    public void processResponse(HeadersFrame frame)
    {
        if (validateAndUpdate(EnumSet.of(FrameState.INITIAL), FrameState.HEADER))
            notifyResponse(frame);
    }

    private void notifyResponse(HeadersFrame frame)
    {
        Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onResponse(this, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    public void processData(DataFrame frame)
    {
        validateAndUpdate(EnumSet.of(FrameState.HEADER, FrameState.DATA), FrameState.DATA);
    }

    public void processTrailer(HeadersFrame frame)
    {
        if (validateAndUpdate(EnumSet.of(FrameState.HEADER, FrameState.DATA), FrameState.TRAILER))
            notifyTrailer(this, frame);
    }

    private void notifyTrailer(HTTP3Stream stream, HeadersFrame frame)
    {
        Stream.Listener listener = stream.getListener();
        try
        {
            if (listener != null)
                listener.onTrailer(stream, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    private boolean validateAndUpdate(EnumSet<FrameState> allowed, FrameState target)
    {
        if (allowed.contains(frameState))
        {
            frameState = target;
            return true;
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("invalid frame sequence, current={}, allowed={}, next={}", frameState, allowed, target);
            if (frameState == FrameState.FAILED)
                return false;
            frameState = FrameState.FAILED;
            session.closeAndNotifyFailure(ErrorCode.FRAME_UNEXPECTED_ERROR.code(), "invalid_frame_sequence");
            return false;
        }
    }

    private Promise.Completable<Stream> writeFrame(Frame frame)
    {
        Promise.Completable<Stream> completable = new Promise.Completable<>();
        session.writeFrame(endPoint.getStreamId(), frame, Callback.from(Invocable.InvocationType.NON_BLOCKING, () -> completable.succeeded(this), completable::failed));
        return completable;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x#%d", getClass().getSimpleName(), hashCode(), getId());
    }

    private enum FrameState
    {
        INITIAL, HEADER, DATA, TRAILER, FAILED
    }
}
