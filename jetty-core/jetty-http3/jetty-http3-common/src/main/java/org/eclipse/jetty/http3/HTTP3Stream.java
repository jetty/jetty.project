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

package org.eclipse.jetty.http3;

import java.util.EnumSet;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HTTP3Stream implements Stream, CyclicTimeouts.Expirable, Attachable
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3Stream.class);

    private final AutoLock lock = new AutoLock();
    private final HTTP3Session session;
    private final StreamEndPoint endPoint;
    private final boolean local;
    private CloseState closeState = CloseState.NOT_CLOSED;
    private FrameState frameState = FrameState.INITIAL;
    private long idleTimeout;
    private long expireNanoTime = Long.MAX_VALUE;
    private Object attachment;
    private boolean dataDemand;
    private boolean dataStalled = true;
    private boolean dataLast;
    private boolean dataAvailable;

    public HTTP3Stream(HTTP3Session session, StreamEndPoint endPoint, boolean local)
    {
        this.session = session;
        this.endPoint = endPoint;
        this.local = local;
    }

    public StreamEndPoint getStreamEndPoint()
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
        return endPoint.getStream().getId();
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
        if (LOG.isDebugEnabled())
            LOG.debug("setting idle timeout {} ms for {}", idleTimeout, this);
        this.idleTimeout = idleTimeout;
        notIdle();
        session.scheduleIdleTimeout(this);
    }

    @Override
    public long getExpireNanoTime()
    {
        return expireNanoTime;
    }

    protected void notIdle()
    {
        expireNanoTime = CyclicTimeouts.Expirable.calcExpireNanoTime(getIdleTimeout());
    }

    void onIdleTimeout(TimeoutException timeout, Promise<Boolean> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("stream idle timeout {} ms expired on {}", getIdleTimeout(), this);
        notifyIdleTimeout(timeout, Promise.from(timedOut ->
        {
            if (timedOut)
            {
                disconnect(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), timeout, Promise.Invocable.noop());
            }
            else
            {
                notIdle();
                // This promise may not be called from the thread that called onIdleTimeout() so CyclicTimeouts.iterate()
                // may not notice that the expireNanoTime field has been updated, so we need to explicitly reschedule the
                // timeout.
                session.scheduleIdleTimeout(this);
            }
            promise.succeeded(timedOut);
        }, promise::failed));
    }

    @Override
    public void data(DataFrame frame, Promise.Invocable<Stream> promise)
    {
        write(frame, promise);
    }

    protected void write(Frame frame, Promise.Invocable<Stream> promise)
    {
        writeFrame(frame, new Promise.Invocable.Abstract<>(promise.getInvocationType())
        {
            @Override
            public void succeeded(Stream result)
            {
                updateClose(Frame.isLast(frame), true);
                promise.succeeded(result);
            }

            @Override
            public void failed(Throwable x)
            {
                updateClose(Frame.isLast(frame), true);
                Promise.Invocable<Stream> p = Promise.Invocable.from(getInvocationType(), s -> promise.failed(x), t -> promise.failed(x));
                disconnect(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x, p);
            }
        });
    }

    @Override
    public Content.Chunk read()
    {
        HTTP3StreamConnection connection = (HTTP3StreamConnection)endPoint.getConnection();
        Content.Chunk chunk = connection.read();

        if (LOG.isDebugEnabled())
            LOG.debug("read {} on {}", chunk, this);

        try (AutoLock ignored = lock.lock())
        {
            dataAvailable = chunk != null;
            dataLast = chunk != null && chunk.isLast();
        }

        if (chunk != null)
            updateClose(chunk.isLast(), false);

        return chunk;
    }

    @Override
    public void demand()
    {
        boolean needsFillInterest;
        boolean process = false;
        try (AutoLock ignored = lock.lock())
        {
            dataDemand = true;
            needsFillInterest = !dataAvailable;
            if (dataStalled && dataAvailable || dataLast)
            {
                dataStalled = false;
                process = true;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("demand, process={} needsFillInterest={} on {}", process, needsFillInterest, this);
        if (process)
        {
            // Data is immediately available.
            processData(true);
        }
        else if (needsFillInterest)
        {
            HTTP3StreamConnection connection = (HTTP3StreamConnection)endPoint.getConnection();
            connection.fillInterested();
        }
    }

    void processData(boolean immediate)
    {
        // Always call onDataAvailable() when this method is called.
        // Previously, dataAvailable was false after the last read(),
        // but now new data is available, and therefore we need to
        // call onDataAvailable().
        boolean notify = true;
        while (true)
        {
            try (AutoLock ignored = lock.lock())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("processing demand={}, dataAvailable={} on {}", dataDemand, dataAvailable, this);
                if ((dataAvailable || notify) && dataDemand)
                {
                    dataDemand = false;
                    dataStalled = false;
                    notify = false;
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Stalling data processing for {}", this);
                    dataStalled = true;
                    return;
                }
            }
            onDataAvailable(immediate);
        }
    }

    @Override
    public void trailer(HeadersFrame frame, Promise.Invocable<Stream> promise)
    {
        if (!frame.isLast())
        {
            promise.failed(new IllegalArgumentException("invalid trailer frame: property 'last' must be true"));
            return;
        }
        write(frame, promise);
    }

    public boolean hasDemand()
    {
        try (AutoLock ignored = lock.lock())
        {
            return dataDemand;
        }
    }

    private boolean isStalled()
    {
        try (AutoLock ignored = lock.lock())
        {
            return dataStalled;
        }
    }

    private boolean isLast()
    {
        try (AutoLock ignored = lock.lock())
        {
            return dataLast;
        }
    }

    public void onHeaders(HeadersFrame frame)
    {
        notIdle();
        try (AutoLock ignored = lock.lock())
        {
            dataLast = frame.isLast();
            // Assume there will be data.
            dataAvailable = !dataLast;
        }
    }

    public void onData(DataFrame ignored)
    {
        validateAndUpdate(EnumSet.of(FrameState.HEADER, FrameState.DATA), FrameState.DATA);
        notIdle();
    }

    private void onDataAvailable(boolean immediate)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("notifying data available on {}", this);
        notifyDataAvailable(immediate);
    }

    protected abstract void notifyDataAvailable(boolean immediate);

    public void onTrailer(HeadersFrame frame)
    {
        Throwable failure = MetaData.Failed.getFailure(frame.getMetaData());
        if (failure != null)
        {
            updateClose(true, false);
            onFailure(HTTP3ErrorCode.PROTOCOL_ERROR.code(), failure);
            return;
        }

        validateAndUpdate(EnumSet.of(FrameState.HEADER, FrameState.DATA), FrameState.TRAILER);
        notIdle();
        updateClose(frame.isLast(), false);
        notifyTrailer(frame);
    }

    protected abstract void notifyTrailer(HeadersFrame frame);

    protected abstract void notifyIdleTimeout(TimeoutException timeout, Promise<Boolean> promise);

    public void onFailure(long error, Throwable failure)
    {
        notifyFailure(error, failure);
        disconnect(error, failure, Promise.Invocable.noop());
    }

    public abstract void notifyFailure(long error, Throwable failure);

    protected void validateAndUpdate(EnumSet<FrameState> allowed, FrameState target)
    {
        if (allowed.contains(frameState))
        {
            frameState = target;
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("invalid frame sequence, current={}, allowed={}, next={}", frameState, allowed, target);
            frameState = FrameState.FAILED;
            throw new HTTP3Exception.SessionException(HTTP3ErrorCode.FRAME_UNEXPECTED_ERROR, "invalid_frame_sequence");
        }
    }

    public void writeFrame(Frame frame, Promise.Invocable<Stream> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("writing {} on {}", frame, this);
        notIdle();
        session.writeMessageFrame(endPoint, frame, Promise.Invocable.toCallback(promise, this));
    }

    private CloseState getCloseState()
    {
        try (AutoLock ignored = lock.lock())
        {
            return closeState;
        }
    }

    public boolean isClosed()
    {
        return getCloseState()  == CloseState.CLOSED;
    }

    public void updateClose(boolean update, boolean local)
    {
        if (!update)
            return;
        boolean remove = false;
        try (AutoLock ignored = lock.lock())
        {
            CloseState oldCloseState = closeState;
            switch (oldCloseState)
            {
                case NOT_CLOSED ->
                {
                    if (local)
                        closeState = CloseState.LOCALLY_CLOSED;
                    else
                        closeState = CloseState.REMOTELY_CLOSED;
                }
                case LOCALLY_CLOSED ->
                {
                    if (!local)
                    {
                        closeState = CloseState.CLOSED;
                        remove = true;
                    }
                }
                case REMOTELY_CLOSED ->
                {
                    if (local)
                    {
                        closeState = CloseState.CLOSED;
                        remove = true;
                    }
                }
                case CLOSED ->
                {
                }
                default -> throw new IllegalStateException();
            }
            if (LOG.isDebugEnabled())
                LOG.debug("updated close {}->{} on {}", oldCloseState, closeState, this);
        }
        if (remove)
            session.removeStream(this);
    }

    @Override
    public void disconnect(long appErrorCode, Throwable failure, Promise.Invocable<Stream> promise)
    {
        disconnect(appErrorCode, failure, failure != null, promise);
    }

    private void disconnect(long appErrorCode, Throwable failure, boolean notifyFailure, Promise.Invocable<Stream> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting with error 0x{} {} {}", Long.toHexString(appErrorCode), this, String.valueOf(failure));
        try (AutoLock ignored = lock.lock())
        {
            closeState = CloseState.CLOSED;
        }

        // TODO: reset data* fields?

        if (notifyFailure)
            notifyFailure(appErrorCode, failure);

        session.removeStream(this);

        // Propagate outwards.
        HTTP3StreamConnection connection = (HTTP3StreamConnection)endPoint.getConnection();
        connection.disconnect(appErrorCode, failure, Promise.Invocable.toPromise(promise, streamEndPoint -> this));
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x#%d[%s,demand=%b,stalled=%b,last=%b,idle=%d/%d,session=%s]",
            TypeUtil.toShortName(getClass()),
            hashCode(),
            getId(),
            getCloseState(),
            hasDemand(),
            isStalled(),
            isLast(),
            NanoTime.millisUntil(expireNanoTime),
            getIdleTimeout(),
            getSession()
        );
    }

    /**
     * <p>Defines the state of the stream for received frames,</p>
     * <p>allowing to verify that a frame sequence is valid for
     * the HTTP protocol.</p>
     * <p>For example, for a stream in the {@link #INITIAL} state,
     * receiving a {@link DataFrame} would move the stream to the
     * {@link #DATA} state which would be invalid, since for the
     * HTTP protocol a {@link HeadersFrame} is expected before
     * any {@link DataFrame}.</p>
     */
    protected enum FrameState
    {
        /**
         * The initial state of the stream, before it receives any frame.
         */
        INITIAL,
        /**
         * The stream has received an HTTP informational response.
         */
        INFORMATIONAL,
        /**
         * The stream has received an HTTP final response.
         */
        HEADER,
        /**
         * The stream has received HTTP content.
         */
        DATA,
        /**
         * The stream has received an HTTP trailer.
         */
        TRAILER,
        /**
         * The stream has encountered a failure.
         */
        FAILED
    }

    private enum CloseState
    {
        NOT_CLOSED, LOCALLY_CLOSED, REMOTELY_CLOSED, CLOSED
    }
}
