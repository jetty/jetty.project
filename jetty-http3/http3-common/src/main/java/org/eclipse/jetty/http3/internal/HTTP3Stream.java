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

public abstract class HTTP3Stream implements Stream, CyclicTimeouts.Expirable, Attachable
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3Stream.class);

    private final HTTP3Session session;
    private final QuicStreamEndPoint endPoint;
    private final boolean local;
    private CloseState closeState = CloseState.NOT_CLOSED;
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
    public HTTP3Session getSession()
    {
        return session;
    }

    public boolean isLocal()
    {
        return local;
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

    protected void notIdle()
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
    public CompletableFuture<Stream> data(DataFrame frame)
    {
        return write(frame);
    }

    protected CompletableFuture<Stream> write(Frame frame)
    {
        return writeFrame(frame)
            .whenComplete((s, x) ->
            {
                if (x == null)
                    updateClose(Frame.isLast(frame), true);
                else
                    session.removeStream(this, x);
            });
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
            if (LOG.isDebugEnabled())
                LOG.debug("could not read {}", this, x);
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
        return write(frame);
    }

    public boolean hasDemand()
    {
        HTTP3StreamConnection connection = (HTTP3StreamConnection)endPoint.getConnection();
        return connection.hasDemand();
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

    protected abstract void notifyDataAvailable();

    public void onTrailer(HeadersFrame frame)
    {
        if (validateAndUpdate(EnumSet.of(FrameState.HEADER, FrameState.DATA), FrameState.TRAILER))
        {
            notIdle();
            notifyTrailer(frame);
            updateClose(frame.isLast(), false);
        }
    }

    protected abstract void notifyTrailer(HeadersFrame frame);

    protected abstract boolean notifyIdleTimeout(TimeoutException timeout);

    public void onFailure(long error, Throwable failure)
    {
        notifyFailure(error, failure);
        session.removeStream(this, failure);
    }

    protected abstract void notifyFailure(long error, Throwable failure);

    protected boolean validateAndUpdate(EnumSet<FrameState> allowed, FrameState target)
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
            session.onSessionFailure(HTTP3ErrorCode.FRAME_UNEXPECTED_ERROR.code(), "invalid_frame_sequence", new IllegalStateException("invalid frame sequence"));
            return false;
        }
    }

    public Promise.Completable<Stream> writeFrame(Frame frame)
    {
        notIdle();
        Promise.Completable<Stream> completable = new Promise.Completable<>();
        session.writeMessageFrame(endPoint.getStreamId(), frame, Callback.from(Invocable.InvocationType.NON_BLOCKING, () -> completable.succeeded(this), completable::failed));
        return completable;
    }

    public boolean isClosed()
    {
        return closeState == CloseState.CLOSED;
    }

    public void updateClose(boolean update, boolean local)
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
                        session.removeStream(this, null);
                    }
                    break;
                }
                case REMOTELY_CLOSED:
                {
                    if (local)
                    {
                        closeState = CloseState.CLOSED;
                        session.removeStream(this, null);
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
        session.removeStream(this, failure);
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

    protected enum FrameState
    {
        INITIAL, CONTINUE, HEADER, DATA, TRAILER, FAILED
    }

    private enum CloseState
    {
        NOT_CLOSED, LOCALLY_CLOSED, REMOTELY_CLOSED, CLOSED
    }
}
