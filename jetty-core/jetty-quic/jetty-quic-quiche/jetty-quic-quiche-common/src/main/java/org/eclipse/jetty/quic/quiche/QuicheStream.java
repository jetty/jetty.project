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

package org.eclipse.jetty.quic.quiche;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.WritePendingException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.common.AbstractStream;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicheStream extends AbstractStream
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicheStream.class);
    private static final Listener DEFAULT_LISTENER = new Listener() {};

    private final AtomicReference<Writer> writer = new AtomicReference<>();
    private final AtomicReference<CloseState> closeState = new AtomicReference<>(CloseState.NOT_CLOSED);
    private final QuicheSession session;

    public QuicheStream(QuicheSession session, long streamId, boolean local)
    {
        super(streamId, local);
        this.session = session;
    }

    @Override
    public boolean isClosed()
    {
        return closeState.get() == CloseState.CLOSED;
    }

    @Override
    public boolean isLocallyClosed()
    {
        return closeState.get() == CloseState.LOCALLY_CLOSED;
    }

    @Override
    public boolean isRemotelyClosed()
    {
        CloseState current = closeState.get();
        return switch (current)
        {
            case NOT_CLOSED, LOCALLY_CLOSED ->
            {
                boolean finished = session.isFinished(this);
                if (finished)
                    updateCloseState(CloseState.REMOTELY_CLOSED);
                yield finished;
            }
            case REMOTELY_CLOSED, CLOSED -> true;
        };
    }

    @Override
    public QuicheSession getSession()
    {
        return session;
    }

    Throwable peek()
    {
        Throwable failure = session.peek(this);
        if (failure != null)
            updateCloseState(CloseState.REMOTELY_CLOSED);
        return failure;
    }

    @Override
    public Data read()
    {
        try
        {
            try
            {
                Data data = session.read(this);
                if (data != null && data.isLast())
                    updateCloseState(CloseState.REMOTELY_CLOSED);
                return data;
            }
            catch (IOException x)
            {
                throw new UncheckedIOException(x);
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure reading from {}", this, x);

            updateCloseState(CloseState.REMOTELY_CLOSED);

            // Stream.read() typically does not throw, but with
            // Quiche we have to make an exception because it
            // does not report events for RESET_STREAM frames.
            // Cannot return null or EOF here, otherwise the caller
            // would be forced to read and then check for errors.
            throw x;
        }
    }

    @Override
    public void demand()
    {
        session.demand(this);
    }

    @Override
    public void data(boolean last, List<ByteBuffer> buffers, Promise.Invocable<Stream> promise)
    {
        Writer current;
        while (true)
        {
            current = writer.get();
            if (current != null)
            {
                promise.failed(new WritePendingException());
                return;
            }
            current = Writer.forWriting(last, buffers, promise);
            if (writer.compareAndSet(null, current))
                break;
        }
        write(current);
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        super.setIdleTimeout(idleTimeout);
        session.scheduleIdleTimeout(this);
    }

    private void write(Writer current)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("writing {} for {}", current, this);

            int length = current.buffers().size();
            for (int i = 0; i < length; ++i)
            {
                ByteBuffer buffer = current.buffers().get(i);

                int remaining = buffer.remaining();
                boolean lastBuffer = i == length - 1;

                if (remaining == 0 && !lastBuffer)
                    continue;

                int written = session.data(this, current.last() && lastBuffer, buffer);
                if (written != remaining)
                {
                    // Write stalled, save state and return.
                    if (LOG.isDebugEnabled())
                        LOG.debug("pending {} for {}", current, this);
                    if (!current.pending())
                    {
                        Writer pending = Writer.forPending(current);
                        // If the CAS fails (e.g. due to asynchronous failures), just return.
                        writer.compareAndSet(current, pending);
                    }
                    session.flush();
                    return;
                }
            }

            session.flush();

            writer.set(null);

            if (current.last())
                updateCloseState(CloseState.LOCALLY_CLOSED);

            if (LOG.isDebugEnabled())
                LOG.debug("written {} for {}", current, this);

            current.promise().succeeded(this);
        }
        catch (Throwable x)
        {
            updateCloseState(CloseState.LOCALLY_CLOSED);
            current.promise().failed(x);
        }
    }

    private void updateCloseState(CloseState event)
    {
        while (true)
        {
            CloseState current = closeState.get();
            switch (current)
            {
                case NOT_CLOSED ->
                {
                    if (closeState.compareAndSet(current, event))
                        return;
                }
                case LOCALLY_CLOSED ->
                {
                    if (event == CloseState.REMOTELY_CLOSED || event == CloseState.CLOSED)
                    {
                        if (!closeState.compareAndSet(current, event))
                            continue;
                        removeAndNotifyClose();
                    }
                    return;
                }
                case REMOTELY_CLOSED ->
                {
                    if (event == CloseState.LOCALLY_CLOSED || event == CloseState.CLOSED)
                    {
                        if (!closeState.compareAndSet(current, event))
                            continue;
                        removeAndNotifyClose();
                    }
                    return;
                }
                case CLOSED ->
                {
                    return;
                }
            }
        }
    }

    private void removeAndNotifyClose()
    {
        session.remove(this);
        notifyClose();
    }

    @Override
    public void maxData(long maxData, Promise.Invocable<Stream> promise)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reset(long appErrorCode, Promise.Invocable<Stream> promise)
    {
        updateCloseState(CloseState.LOCALLY_CLOSED);
        session.shutdownStream(this, true, appErrorCode, promise);
    }

    @Override
    public void stopSending(long appErrorCode, Promise.Invocable<Stream> promise)
    {
        // Ask the other peer to stop sending, but there may be
        // data in flight, so cannot update the close state here.
        session.shutdownStream(this, false, appErrorCode, promise);
    }

    @Override
    public void dataBlocked(long offset, Promise.Invocable<Stream> promise)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void disconnect(long appErrorCode, Throwable failure, Promise.Invocable<Stream> promise)
    {
        disconnect(true, appErrorCode, failure, promise);
    }

    void disconnect(boolean stopAndReset, long appErrorCode, Throwable failure, Promise.Invocable<Stream> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting with error 0x{} stop&reset={} {} {}", Long.toHexString(appErrorCode), stopAndReset, this, String.valueOf(failure));

        Writer writer = this.writer.get();
        if (writer != null)
            writer.promise().failed(failure != null ? failure : new AsynchronousCloseException());

        CloseState previous = closeState.getAndSet(CloseState.CLOSED);
        if (previous != CloseState.CLOSED)
            removeAndNotifyClose();

        if (!stopAndReset || previous == CloseState.CLOSED)
        {
            promise.succeeded(this);
            return;
        }

        boolean stopSending = previous != CloseState.REMOTELY_CLOSED;
        boolean reset = previous != CloseState.LOCALLY_CLOSED;

        if (stopSending)
        {
            if (reset)
                stopSending(appErrorCode, Promise.Invocable.from(promise.getInvocationType(), s -> reset(appErrorCode, promise), promise::failed));
            else
                stopSending(appErrorCode, promise);
        }
        else
        {
            if (reset)
                reset(appErrorCode, promise);
            else
                promise.succeeded(this);
        }
    }

    void onIdleTimeout(TimeoutException timeout)
    {
        boolean expired = notifyIdleTimeout(timeout);

        if (LOG.isDebugEnabled())
            LOG.debug("stream idle timeout {}ms {} on {}", getIdleTimeout(), expired ? "expired" : "ignored", this);

        if (expired)
            disconnect(ErrorCode.NO_ERROR.code(), timeout, Promise.Invocable.noop());
        else
            notIdle();
    }

    void resumeWrite()
    {
        Writer current = writer.get();
        if (current == null)
            return;
        if (!current.pending)
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("resuming write pending for {}", this);
        write(current);
    }

    void tryFailWrite()
    {
        Writer current = writer.get();
        if (current == null)
            return;
        if (!current.pending)
            return;
        if (!session.isFailed(this))
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("failing write pending for {}", this);
        write(current);
    }

    void notifyDataAvailable()
    {
        Listener listener = Objects.requireNonNullElse(getListener(), DEFAULT_LISTENER);
        try
        {
            listener.onDataAvailable(this);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying listener {}", listener, x);
        }
    }

    private boolean notifyIdleTimeout(TimeoutException failure)
    {
        Listener listener = getListener();
        try
        {
            if (listener != null)
                return listener.onIdleTimeout(this, failure);
            return true;
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying listener {}", listener, x);
            return true;
        }
    }

    void notifyFailure(Throwable failure)
    {
        Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onFailure(this, failure);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying listener {}", listener, x);
        }
    }

    private void notifyClose()
    {
        Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onClose(this);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying listener {}", listener, x);
        }
    }

    @Override
    public String toString()
    {
        return "%s[%s,writer=%s]".formatted(super.toString(), closeState, writer);
    }

    private record Writer(boolean last, List<ByteBuffer> buffers, Promise.Invocable<Stream> promise, boolean pending)
    {
        private static Writer forWriting(boolean last, List<ByteBuffer> buffers, Promise.Invocable<Stream> promise)
        {
            return new Writer(last, buffers, promise, false);
        }

        public static Writer forPending(Writer writer)
        {
            return new Writer(writer.last, writer.buffers, writer.promise, true);
        }

        @Override
        public String toString()
        {
            return "%s@%x[last=%b,pending=%b,buffers=%s]".formatted(
                TypeUtil.toShortName(getClass()),
                hashCode(),
                last,
                pending,
                buffers
            );
        }
    }

    private enum CloseState
    {
        NOT_CLOSED, LOCALLY_CLOSED, REMOTELY_CLOSED, CLOSED
    }
}
