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
import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3Stream implements Stream, CyclicTimeouts.Expirable, Attachable
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3Stream.class);

    private final HTTP3Session session;
    private final QuicStreamEndPoint endPoint;
    private final boolean local;
    private CloseState closeState = CloseState.NOT_CLOSED;
    private Listener listener;
    private FrameState frameState = FrameState.INITIAL;
    private long idleTimeout;
    private long expireNanoTime;
    private Object attachment;

    public HTTP3Stream(HTTP3Session session, QuicStreamEndPoint endPoint, boolean local)
    {
        this.session = session;
        this.endPoint = endPoint;
        this.local = local;
    }

    public QuicStreamEndPoint getEndPoint()
    {
        return endPoint;
    }

    @Override
    public Object getAttachment()
    {
        return attachment;
    }

    @Override
    public void setAttachment(Object attachment)
    {
        this.attachment = attachment;
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

    public boolean isLocal()
    {
        return local;
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

    boolean onIdleTimeout(TimeoutException timeout)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout {} ms expired on {}", getIdleTimeout(), this);
        boolean close = notifyIdleTimeout(timeout);
        if (close)
            endPoint.close(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), timeout);
        return close;
    }

    @Override
    public CompletableFuture<Stream> respond(HeadersFrame frame)
    {
        Promise.Completable<Stream> completable = writeFrame(frame);
        updateClose(frame.isLast(), true);
        return completable;
    }

    @Override
    public CompletableFuture<Stream> data(DataFrame frame)
    {
        Promise.Completable<Stream> completable = writeFrame(frame);
        updateClose(frame.isLast(), true);
        return completable;
    }

    @Override
    public Data readData()
    {
        try
        {
            HTTP3StreamConnection connection = (HTTP3StreamConnection)endPoint.getConnection();
            Data data = connection.readData();
            if (data != null)
                updateClose(data.isLast(), false);
            return data;
        }
        catch (Throwable x)
        {
            reset(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x);
            // Rethrow to the application, so don't notify onFailure().
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

    public void onRequest(HeadersFrame frame)
    {
        if (validateAndUpdate(EnumSet.of(FrameState.INITIAL), FrameState.HEADER))
        {
            notIdle();
            Listener listener = notifyRequest(frame);
            setListener(listener);
            if (listener == null)
            {
                Callback callback = Callback.from(Invocable.InvocationType.NON_BLOCKING, () -> endPoint.shutdownInput(HTTP3ErrorCode.NO_ERROR.code()));
                session.writeMessageFrame(getId(), new HTTP3Flusher.FlushFrame(), callback);
            }
            updateClose(frame.isLast(), false);
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

    public void onResponse(HeadersFrame frame)
    {
        if (validateAndUpdate(EnumSet.of(FrameState.INITIAL), FrameState.HEADER))
        {
            notIdle();
            notifyResponse(frame);
            updateClose(frame.isLast(), false);
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

    public void onData(DataFrame frame)
    {
        if (validateAndUpdate(EnumSet.of(FrameState.HEADER, FrameState.DATA), FrameState.DATA))
            notIdle();
    }

    public void onDataAvailable()
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

    public void onTrailer(HeadersFrame frame)
    {
        if (validateAndUpdate(EnumSet.of(FrameState.HEADER, FrameState.DATA), FrameState.TRAILER))
        {
            notIdle();
            notifyTrailer(frame);
            updateClose(frame.isLast(), false);
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

    public void onFailure(Throwable failure)
    {
        notifyFailure(failure);
        session.removeStream(this);
    }

    private void notifyFailure(Throwable failure)
    {
        Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onFailure(this, failure);
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
            session.fail(HTTP3ErrorCode.FRAME_UNEXPECTED_ERROR.code(), "invalid_frame_sequence");
            return false;
        }
    }

    Promise.Completable<Stream> writeFrame(Frame frame)
    {
        notIdle();
        Promise.Completable<Stream> completable = new Promise.Completable<>();
        session.writeMessageFrame(endPoint.getStreamId(), frame, Callback.from(Invocable.InvocationType.NON_BLOCKING, () -> completable.succeeded(this), completable::failed));
        return completable;
    }

    void updateClose(boolean update, boolean local)
    {
        if (update)
        {
            switch (closeState)
            {
                case NOT_CLOSED:
                {
                    closeState = local ? CloseState.LOCALLY_CLOSED : CloseState.REMOTELY_CLOSED;
                    break;
                }
                case LOCALLY_CLOSED:
                {
                    if (!local)
                    {
                        closeState = CloseState.CLOSED;
                        session.removeStream(this);
                    }
                    break;
                }
                case REMOTELY_CLOSED:
                {
                    if (local)
                    {
                        closeState = CloseState.CLOSED;
                        session.removeStream(this);
                    }
                    break;
                }
                case CLOSED:
                {
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
    }

    @Override
    public void reset(long error, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("resetting {} with error 0x{} {}", this, Long.toHexString(error), failure.toString());
        closeState = CloseState.CLOSED;
        session.removeStream(this);
        endPoint.close(error, failure);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x#%d[demand=%b,idle=%d,session=%s]",
            getClass().getSimpleName(),
            hashCode(),
            getId(),
            hasDemand(),
            TimeUnit.NANOSECONDS.toMillis(expireNanoTime - System.nanoTime()),
            getSession()
        );
    }

    private enum FrameState
    {
        INITIAL, HEADER, DATA, TRAILER, FAILED
    }

    private enum CloseState
    {
        NOT_CLOSED, LOCALLY_CLOSED, REMOTELY_CLOSED, CLOSED
    }
}
