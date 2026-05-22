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

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.WritePendingException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.common.AbstractStream;
import org.eclipse.jetty.quic.common.QuicConfiguration;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicheStream extends AbstractStream
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicheStream.class);
    private static final Listener DEFAULT_LISTENER = new Listener() {};

    private final AutoLock lock = new AutoLock();
    private final AtomicReference<Writer> writer = new AtomicReference<>();
    private final AtomicReference<CloseState> closeState = new AtomicReference<>(CloseState.NOT_CLOSED);
    private final QuicheSession session;
    private RetainableByteBuffer inputBuffer;
    private Content.Chunk chunk;
    private boolean dataDemand;

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

    void readable()
    {
        boolean hasDemand;
        try (AutoLock ignored = lock.lock())
        {
            hasDemand = dataDemand;
            dataDemand = false;
        }

        boolean streamFinished = session.isFinished(this);
        Throwable resetFailure = streamFinished ? isReset() : null;

        if (LOG.isDebugEnabled())
            LOG.debug("readable demand={} finished={} reset={} {} on {}", hasDemand, streamFinished, resetFailure != null, this, session);

        if (hasDemand && resetFailure == null)
            notifyDataAvailable();
        else if (resetFailure != null)
            notifyFailure(resetFailure);
    }

    private Throwable isReset()
    {
        Throwable failure = session.isReset(this);
        if (failure != null)
            updateCloseState(CloseState.REMOTELY_CLOSED);
        return failure;
    }

    @Override
    public Content.Chunk read()
    {
        RetainableByteBuffer inputBuffer;
        try (AutoLock ignored = lock.lock())
        {
            if (chunk != null)
                return chunk;
            inputBuffer = this.inputBuffer;
            this.inputBuffer = null;
        }
        inputBuffer = tryAcquireInputBuffer(inputBuffer);

        try
        {
            ByteBuffer byteBuffer = inputBuffer.getByteBuffer();
            int position = byteBuffer.position();
            byteBuffer.limit(byteBuffer.capacity());

            boolean finished = session.isFinished(this);
            if (LOG.isDebugEnabled())
                LOG.debug("finished={} on {}", finished, this);
            if (finished)
            {
                updateCloseState(CloseState.REMOTELY_CLOSED);
                tryReleaseInputBuffer(inputBuffer);
                return Content.Chunk.EOF;
            }

            boolean[] outLast = new boolean[1];
            int filled = session.read(this, byteBuffer, outLast);
            BufferUtil.flipToFlush(byteBuffer, position);
            boolean last = outLast[0];
            if (LOG.isDebugEnabled())
                LOG.debug("read {} bytes last={} on {}", filled, last, this);
            if (last)
                updateCloseState(CloseState.REMOTELY_CLOSED);

            if (filled > 0)
            {
                ByteBuffer slice = byteBuffer.slice();
                byteBuffer.position(byteBuffer.limit());
                // Retain because multiple chunks can be read from the same inputBuffer.
                inputBuffer.retain();
                Content.Chunk chunk = Content.Chunk.asChunk(slice, last, inputBuffer);
                if (last)
                    tryReleaseInputBuffer(inputBuffer);
                else
                    tryStoreInputBuffer(inputBuffer);
                return chunk;
            }

            if (filled == 0 && !last)
            {
                // Keep the buffer around only if it is retained.
                if (inputBuffer.isRetained())
                    tryStoreInputBuffer(inputBuffer);
                else
                    tryReleaseInputBuffer(inputBuffer);
                return null;
            }

            tryReleaseInputBuffer(inputBuffer);
            return Content.Chunk.EOF;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure reading from {}", this, x);

            Content.Chunk failure;
            try (AutoLock ignored = lock.lock())
            {
                chunk = failure = Content.Chunk.from(x);
            }
            tryReleaseInputBuffer(inputBuffer);
            updateCloseState(CloseState.REMOTELY_CLOSED);
            return failure;
        }
    }

    private RetainableByteBuffer tryAcquireInputBuffer(RetainableByteBuffer buffer)
    {
        QuicConfiguration quicConfiguration = session.getQuicConfiguration();
        if (buffer != null)
        {
            int minInputSpace = quicConfiguration.getMinInputBufferSpace();
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            if (minInputSpace < 0 || (byteBuffer.capacity() - byteBuffer.limit()) < minInputSpace)
            {
                tryReleaseInputBuffer(buffer);
                buffer = null;
            }
        }
        RetainableByteBuffer candidate = buffer;
        if (candidate == null)
            candidate = getSession().getByteBufferPool().acquire(quicConfiguration.getInputBufferSize(), quicConfiguration.isUseInputDirectByteBuffers());
        if (LOG.isDebugEnabled())
            LOG.debug("acquired {} {} on {}", buffer == null ? "fresh" : "stored", candidate, this);
        return candidate;
    }

    private void tryStoreInputBuffer(RetainableByteBuffer buffer)
    {
        try (AutoLock ignored = lock.lock())
        {
            assert inputBuffer == null;
            if (chunk == null)
            {
                inputBuffer = buffer;
                if (LOG.isDebugEnabled())
                    LOG.debug("stored {} on {}", buffer, this);
                return;
            }
        }
        tryReleaseInputBuffer(buffer);
    }

    private void tryReleaseInputBuffer(RetainableByteBuffer buffer)
    {
        if (buffer != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("releasing {} on {}", buffer, this);
            buffer.release();
        }
    }

    @Override
    public void demand()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("demand for {} on {}", this, session);

        try (AutoLock ignored = lock.lock())
        {
            dataDemand = true;
        }

        // The production might have idled, as all the network
        // data was fed to Quiche, but there was no demand.
        // Restart production now that there is demand.
        session.dispatch();
    }

    @Override
    public void data(boolean last, RetainableByteBuffer buffer, Promise.Invocable<Stream> promise)
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
            current = Writer.forWriting(last, buffer, promise);
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

            // TODO: restore
//            int length = current.buffer().size();
//            for (int i = 0; i < length; ++i)
//            {
//                ByteBuffer buffer = current.buffer().get(i);
//
//                int remaining = buffer.remaining();
//                boolean lastBuffer = i == length - 1;
//
//                if (remaining == 0 && !lastBuffer)
//                    continue;
//
//                int written = session.data(this, current.last() && lastBuffer, buffer);
//                if (written != remaining)
//                {
//                    // Write stalled, save state and return.
//                    if (LOG.isDebugEnabled())
//                        LOG.debug("pending {} for {}", current, this);
//                    if (!current.pending())
//                    {
//                        Writer pending = Writer.forPending(current);
//                        // If the CAS fails (e.g. due to asynchronous failures), just return.
//                        writer.compareAndSet(current, pending);
//                    }
//                    session.flush();
//                    return;
//                }
//            }

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
                        if (!closeState.compareAndSet(current, CloseState.CLOSED))
                            continue;
                        removeAndNotifyClose();
                    }
                    return;
                }
                case REMOTELY_CLOSED ->
                {
                    if (event == CloseState.LOCALLY_CLOSED || event == CloseState.CLOSED)
                    {
                        if (!closeState.compareAndSet(current, CloseState.CLOSED))
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
        if (session.remove(this))
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

        RetainableByteBuffer buffer;
        try (AutoLock ignored = lock.lock())
        {
            chunk = Content.Chunk.from(failure);
            buffer = inputBuffer;
            inputBuffer = null;
        }
        tryReleaseInputBuffer(buffer);

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
        notifyIdleTimeout(timeout, Promise.Invocable.from(Invocable.InvocationType.NON_BLOCKING, (expired, x) ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("stream idle timeout {}ms {} on {}", getIdleTimeout(), expired ? "expired" : "ignored", this);
            if (x == null)
            {
                if (expired)
                    disconnect(ErrorCode.NO_ERROR.code(), timeout, Promise.Invocable.noop());
                else
                    notIdle();
            }
            else
            {
                disconnect(ErrorCode.NO_ERROR.code(), ExceptionUtil.combine(timeout, x), Promise.Invocable.noop());
            }
        }));
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

    private void notifyDataAvailable()
    {
        Stream.Listener listener = Objects.requireNonNullElse(getListener(), DEFAULT_LISTENER);
        try
        {
            listener.onDataAvailable(this, false);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying listener {}", listener, x);
        }
    }

    private void notifyIdleTimeout(TimeoutException failure, Promise.Invocable<Boolean> promise)
    {
        Stream.Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onIdleTimeout(this, failure, promise);
            else
                promise.succeeded(true);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying listener {}", listener, x);
            promise.failed(x);
        }
    }

    @Override
    public String toString()
    {
        return "%s[%s,writer=%s]".formatted(super.toString(), closeState, writer);
    }

    private record Writer(boolean last, RetainableByteBuffer buffer, Promise.Invocable<Stream> promise, boolean pending)
    {
        private static Writer forWriting(boolean last, RetainableByteBuffer buffer, Promise.Invocable<Stream> promise)
        {
            return new Writer(last, buffer, promise, false);
        }

        public static Writer forPending(Writer writer)
        {
            return new Writer(writer.last, writer.buffer, writer.promise, true);
        }

        @Override
        public String toString()
        {
            return "%s@%x[last=%b,pending=%b,buffers=%s]".formatted(
                TypeUtil.toShortName(getClass()),
                hashCode(),
                last,
                pending,
                buffer
            );
        }
    }

    private enum CloseState
    {
        NOT_CLOSED, LOCALLY_CLOSED, REMOTELY_CLOSED, CLOSED
    }
}
