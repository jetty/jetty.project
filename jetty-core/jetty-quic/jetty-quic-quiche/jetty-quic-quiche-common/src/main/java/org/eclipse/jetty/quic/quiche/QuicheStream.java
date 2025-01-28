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
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.common.AbstractStream;
import org.eclipse.jetty.quic.util.ErrorCode;
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
    public CompletableFuture<Stream> data(boolean last, ByteBuffer... buffers)
    {
        Writer current;
        while (true)
        {
            current = writer.get();
            if (current != null)
                return CompletableFuture.failedFuture(new WritePendingException());
            current = Writer.forWriting(buffers, last);
            if (writer.compareAndSet(null, current))
                break;
        }
        write(current);
        return current.completable;
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

            int length = current.buffers().length;
            for (int i = 0; i < length; ++i)
            {
                ByteBuffer buffer = current.buffers()[i];

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

            current.completable().complete(this);
        }
        catch (Throwable x)
        {
            updateCloseState(CloseState.LOCALLY_CLOSED);
            current.completable().completeExceptionally(x);
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
    public CompletableFuture<Stream> maxData(long maxData)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Stream> reset(long appErrorCode)
    {
        updateCloseState(CloseState.LOCALLY_CLOSED);
        return session.shutdownStream(this, true, appErrorCode);
    }

    @Override
    public CompletableFuture<Stream> stopSending(long appErrorCode)
    {
        // Ask the other peer to stop sending, but there may be
        // data in flight, so cannot update the close state here.
        return session.shutdownStream(this, false, appErrorCode);
    }

    @Override
    public CompletableFuture<Stream> dataBlocked(long offset)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Stream> disconnect(long appErrorCode, Throwable failure)
    {
        return disconnect(appErrorCode, failure, true);
    }

    CompletableFuture<Stream> disconnect(long appErrorCode, Throwable failure, boolean stopAndReset)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting with error 0x{} stop&reset={} {} {}", Long.toHexString(appErrorCode), stopAndReset, this, String.valueOf(failure));

        Writer writer = this.writer.get();
        if (writer != null)
            writer.completable().completeExceptionally(failure != null ? failure : new AsynchronousCloseException());

        CloseState previous = closeState.getAndSet(CloseState.CLOSED);
        if (previous != CloseState.CLOSED)
            removeAndNotifyClose();

        if (!stopAndReset || previous == CloseState.CLOSED)
            return CompletableFuture.completedFuture(this);

        CompletableFuture<Stream> result = CompletableFuture.completedFuture(this);
        if (previous != CloseState.REMOTELY_CLOSED)
            result = result.handle((r, x) -> stopSending(appErrorCode)).thenCompose(Function.identity());
        if (previous != CloseState.LOCALLY_CLOSED)
            result = result.handle((r, x) -> reset(appErrorCode)).thenCompose(Function.identity());
        return result;
    }

    void onIdleTimeout(TimeoutException timeout)
    {
        boolean expired = notifyIdleTimeout(timeout);

        if (LOG.isDebugEnabled())
            LOG.debug("stream idle timeout {}ms {} on {}", getIdleTimeout(), expired ? "expired" : "ignored", this);

        if (expired)
            disconnect(ErrorCode.NO_ERROR.code(), timeout);
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

    private record Writer(CompletableFuture<Stream> completable, ByteBuffer[] buffers, boolean last, boolean pending)
    {
        private static Writer forWriting(ByteBuffer[] buffers, boolean last)
        {
            return new Writer(new CompletableFuture<>(), buffers, last, false);
        }

        public static Writer forPending(Writer writer)
        {
            return new Writer(writer.completable, writer.buffers, writer.last, true);
        }

        @Override
        public String toString()
        {
            return "%s@%x[last=%b,pending=%b,buffers=%s]".formatted(
                TypeUtil.toShortName(getClass()),
                hashCode(),
                last,
                pending,
                Arrays.toString(buffers)
            );
        }
    }

    private enum CloseState
    {
        NOT_CLOSED, LOCALLY_CLOSED, REMOTELY_CLOSED, CLOSED
    }
}
