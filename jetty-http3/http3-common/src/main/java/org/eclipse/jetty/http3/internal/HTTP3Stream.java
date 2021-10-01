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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3Stream implements Stream, CyclicTimeouts.Expirable
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3Stream.class);

    private final HTTP3Session session;
    private final QuicStreamEndPoint endPoint;
    private Listener listener;
    private FrameState frameState = FrameState.INITIAL;
    private long idleTimeout;
    private long expireNanoTime;

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

    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
        notIdle();
        session.scheduleIdleTimeout(this);
        if (LOG.isDebugEnabled())
            LOG.debug("set idle timeout {} ms for {}", idleTimeout, this);
    }

    @Override
    public long getExpireNanoTime()
    {
        return expireNanoTime;
    }

    private void notIdle()
    {
        long idleTimeout = getIdleTimeout();
        if (idleTimeout > 0)
            expireNanoTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(idleTimeout);
    }

    boolean processIdleTimeout(TimeoutException timeout)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout {} ms expired on {}", getIdleTimeout(), this);
        boolean close = notifyIdleTimeout(timeout);
        if (close)
            endPoint.close(ErrorCode.REQUEST_CANCELLED_ERROR.code(), timeout);
        return close;
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
        try
        {
            HTTP3StreamConnection connection = (HTTP3StreamConnection)endPoint.getConnection();
            return connection.readData();
        }
        catch (Throwable x)
        {
            session.removeStream(this);
            throw x;
        }
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
            notIdle();
            Listener listener = notifyRequest(frame);
            setListener(listener);
            if (listener == null)
            {
                Callback callback = Callback.from(Invocable.InvocationType.NON_BLOCKING, () -> endPoint.shutdownInput(ErrorCode.NO_ERROR.code()));
                session.writeFrame(getId(), new HTTP3Flusher.FlushFrame(), callback);
            }
        }
    }

    private Listener notifyRequest(HeadersFrame frame)
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
        {
            notIdle();
            notifyResponse(frame);
        }
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
        if (validateAndUpdate(EnumSet.of(FrameState.HEADER, FrameState.DATA), FrameState.DATA))
            notIdle();
    }

    public void processDataAvailable()
    {
        notifyDataAvailable();
    }

    private void notifyDataAvailable()
    {
        Stream.Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onDataAvailable(this);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    public void processTrailer(HeadersFrame frame)
    {
        if (validateAndUpdate(EnumSet.of(FrameState.HEADER, FrameState.DATA), FrameState.TRAILER))
        {
            notIdle();
            notifyTrailer(frame);
        }
    }

    private void notifyTrailer(HeadersFrame frame)
    {
        Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onTrailer(this, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    private boolean notifyIdleTimeout(TimeoutException timeout)
    {
        Listener listener = getListener();
        try
        {
            if (listener != null)
                return listener.onIdleTimeout(this, timeout);
            return true;
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
            return true;
        }
    }

    public void processFailure(long error, Throwable failure)
    {
        notifyFailure(error, failure);
    }

    private void notifyFailure(long error, Throwable failure)
    {
        Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onFailure(error, failure);
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
        notIdle();
        Promise.Completable<Stream> completable = new Promise.Completable<>();
        session.writeFrame(endPoint.getStreamId(), frame, Callback.from(Invocable.InvocationType.NON_BLOCKING, () -> completable.succeeded(this), completable::failed));
        return completable;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x#%d[demand=%b,idle=%d]",
            getClass().getSimpleName(),
            hashCode(),
            getId(),
            hasDemand(),
            TimeUnit.NANOSECONDS.toMillis(expireNanoTime - System.nanoTime())
        );
    }

    private enum FrameState
    {
        INITIAL, HEADER, DATA, TRAILER, FAILED
    }
}
