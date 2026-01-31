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

package org.eclipse.jetty.quic.common;

import java.nio.channels.WritePendingException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.ResetFrame;
import org.eclipse.jetty.quic.api.frames.StopSendingFrame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;
import org.eclipse.jetty.quic.common.frames.FrameStream;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicStream extends AbstractStream
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicStream.class);

    private final AutoLock lock = new AutoLock();
    private final FrameStream frameStream = new FrameStream(this::processDataFrame);
    private final Deque<Content.Chunk> dataQueue = new ArrayDeque<>(1);
    private final AtomicReference<CloseState> closeState = new AtomicReference<>(CloseState.NOT_CLOSED);
    private final Sender sender = new Sender();
    private final AtomicLong sendMaxData = new AtomicLong();
    private final AtomicLong sendData = new AtomicLong();
    private final QuicSession session;
    private boolean readDemand;
    private boolean readStalled;
    private boolean writeStalled;

    public QuicStream(QuicSession session, long streamId, boolean local)
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
            case NOT_CLOSED, LOCALLY_CLOSED, LOCALLY_CLOSING -> false;
            case REMOTELY_CLOSED, CLOSING, CLOSED -> true;
        };
    }

    @Override
    public QuicSession getSession()
    {
        return session;
    }

    @Override
    public Content.Chunk read()
    {
        Content.Chunk chunk;
        try (var _ = lock.lock())
        {
            chunk = dataQueue.poll();
            if (chunk == null)
                return null;
            if (chunk.isLast())
                dataQueue.offer(Content.Chunk.EOF);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("reading {} on {}", chunk, this);

        boolean closed = false;
        if (chunk.isLast())
            closed = updateCloseState(CloseState.REMOTELY_CLOSED);

        if (closed)
            removeAndNotifyClose();
        else
            notIdle();

        return chunk;
    }

    @Override
    public void demand()
    {
        boolean process = false;
        try (var _ = lock.lock())
        {
            readDemand = true;
            if (readStalled && !dataQueue.isEmpty())
            {
                readStalled = false;
                process = true;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("demand, {} data processing on {}", process ? "proceeding" : "stalling", this);
        if (process)
        {
            // Data is immediately available.
            processData(true);
        }
    }

    private boolean hasDemand()
    {
        try (var _ = lock.lock())
        {
            return readDemand;
        }
    }

    @Override
    public void data(boolean last, RetainableByteBuffer data, Promise.Invocable<Stream> promise)
    {
        try
        {
            // Avoid infinite buffering in the session flusher.
            if (!sender.begin(last, promise))
                throw new WritePendingException();

            // If already locally closed, fail the write.
            if (last && !initiateLocalClose())
                throw new IllegalStateException("stream_closed");

            session.data(this, new StreamFrame(getId(), data, last), sender);
        }
        catch (Throwable x)
        {
            promise.failed(x);
        }
    }

    @Override
    public void maxData(long maxData, Promise.Invocable<Stream> promise)
    {
        session.maxData(this, new StreamMaxDataFrame(getId(), maxData), promise);
    }

    void updateSendMaxData(long maxData)
    {
        Atomics.updateMax(sendMaxData, maxData);
    }

    long getSendWindow()
    {
        return sendMaxData.get() - getSendData();
    }

    long getSendData()
    {
        return sendData.get();
    }

    void updateSendData(long sent)
    {
        sendData.addAndGet(sent);
    }

    public boolean stall()
    {
        boolean result = !writeStalled;
        writeStalled = true;
        return result;
    }

    @Override
    public void reset(long appErrorCode, Promise.Invocable<Stream> promise)
    {
        if (!initiateLocalClose())
            promise.failed(new IllegalStateException("stream_closed"));

        session.reset(this, new ResetFrame(getId(), appErrorCode, sendData.get()), Promise.Invocable.from(promise, () ->
        {
            if (updateCloseState(CloseState.LOCALLY_CLOSED))
                removeAndNotifyClose();
        }));
    }

    @Override
    public void stopSending(long appErrorCode, Promise.Invocable<Stream> promise)
    {
        session.stopSending(this, new StopSendingFrame(getId(), appErrorCode), promise);
    }

    @Override
    public void dataBlocked(long offset, Promise.Invocable<Stream> promise)
    {
        session.dataBlocked(this, new StreamDataBlockedFrame(getId(), offset), promise);
    }

    @Override
    public void disconnect(long appErrorCode, Throwable failure, Promise.Invocable<Stream> promise)
    {
        // TODO
    }

    /// Main entry point to process incoming frames received by [QuicSession].
    public void processFrame(Frame.WithStreamId frame)
    {
        notIdle();
        switch (frame)
        {
            case ResetFrame resetFrame -> frameStream.offer(resetFrame);
            case StopSendingFrame stopSendingFrame -> notifyStopSendingFrame(stopSendingFrame);
            case StreamDataBlockedFrame streamDataBlockedFrame -> notifyDataBlockedFrame(streamDataBlockedFrame);
            case StreamFrame streamFrame -> frameStream.offer(streamFrame);
            case StreamMaxDataFrame streamMaxDataFrame -> notifyMaxDataFrame(streamMaxDataFrame);
        }
    }

    private void processDataFrame(Frame frame)
    {
        switch (frame)
        {
            case ResetFrame resetFrame -> processResetFrame(resetFrame);
            case StreamFrame streamFrame -> processStreamFrame(streamFrame);
            default -> throw new AssertionError("unexpected_frame");
        }
    }

    private void processResetFrame(ResetFrame resetFrame)
    {
        updateCloseState(CloseState.REMOTELY_CLOSED);
        // TODO: queue a failure chunk, and notify through that, rather than onReset()?
        notifyResetFrame(resetFrame);
    }

    private void notifyResetFrame(ResetFrame frame)
    {
        Listener listener = getListener();
        try
        {
            listener.onReset(this, frame);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.atDebug().setCause(x).log("failure while notifying listener {}", listener);
        }
    }

    private void processStreamFrame(StreamFrame frame)
    {
        boolean process;
        try (var _ = lock.lock())
        {
            process = dataQueue.isEmpty() && readDemand;
            Content.Chunk chunk = Content.Chunk.from(frame.data(), frame.isEndStream());
            // Retain the chunk because it is stored for later use.
            chunk.retain();
            dataQueue.offer(chunk);
            if (LOG.isDebugEnabled())
                LOG.debug("data {} notify={} onDataAvailable() on {}", chunk, process, this);
        }

        if (process)
            processData(false);
    }

    private void processData(boolean immediate)
    {
        while (true)
        {
            try (var _ = lock.lock())
            {
                if (dataQueue.isEmpty() || !readDemand)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("stalling data processing on {}", this);
                    readStalled = true;
                    return;
                }
                readDemand = false;
                readStalled = false;
            }
            notifyDataAvailable(immediate);
        }
    }

    private boolean initiateLocalClose()
    {
        while (true)
        {
            CloseState current = closeState.get();
            switch (current)
            {
                case NOT_CLOSED ->
                {
                    if (closeState.compareAndSet(current, CloseState.LOCALLY_CLOSING))
                        return true;
                }
                case REMOTELY_CLOSED ->
                {
                    if (closeState.compareAndSet(current, CloseState.CLOSING))
                        return true;
                }
                case LOCALLY_CLOSING, LOCALLY_CLOSED, CLOSING, CLOSED ->
                {
                    return false;
                }
            }
        }
    }

    private boolean updateCloseState(CloseState event)
    {
        // The event can only be a subset of the states.
        // LOCALLY_CLOSING -> local closing initiated.
        // LOCALLY_CLOSED -> local closing completed.
        // REMOTELY_CLOSED -> remote closing.
        // CLOSED -> failures.
        boolean valid = switch (event)
        {
            case LOCALLY_CLOSING, LOCALLY_CLOSED, REMOTELY_CLOSED, CLOSED -> true;
            default -> false;
        };
        if (!valid)
            throw new IllegalArgumentException("unexpected_close_event_" + event.name().toLowerCase(Locale.ROOT));

        while (true)
        {
            CloseState current = closeState.get();
            switch (current)
            {
                case NOT_CLOSED ->
                {
                    if (closeState.compareAndSet(current, event))
                        return event == CloseState.CLOSED;
                }
                case LOCALLY_CLOSING ->
                {
                    switch (event)
                    {
                        case LOCALLY_CLOSED, CLOSED ->
                        {
                            if (closeState.compareAndSet(current, event))
                                return event == CloseState.CLOSED;
                        }
                        case REMOTELY_CLOSED ->
                        {
                            if (closeState.compareAndSet(current, CloseState.CLOSING))
                                return false;
                        }
                        default ->
                        {
                            return false;
                        }
                    }
                    ;
                }
                case LOCALLY_CLOSED ->
                {
                    switch (event)
                    {
                        case REMOTELY_CLOSED, CLOSED ->
                        {
                            if (closeState.compareAndSet(current, CloseState.CLOSED))
                                return true;
                        }
                        default ->
                        {
                            return false;
                        }
                    }
                }
                case REMOTELY_CLOSED, CLOSING ->
                {
                    switch (event)
                    {
                        case LOCALLY_CLOSED, CLOSED ->
                        {
                            if (closeState.compareAndSet(current, CloseState.CLOSED))
                                return true;
                        }
                        default ->
                        {
                            return false;
                        }
                    }
                }
                case CLOSED ->
                {
                    return false;
                }
            }
        }
    }

    private void removeAndNotifyClose()
    {
        if (session.remove(this))
            notifyClose();
    }

    private void notifyDataAvailable(boolean immediate)
    {
        Listener listener = getListener();
        try
        {
            listener.onDataAvailable(this, immediate);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    private void notifyDataBlockedFrame(StreamDataBlockedFrame frame)
    {
        Listener listener = getListener();
        try
        {
            listener.onDataBlocked(this, frame);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    private void notifyStopSendingFrame(StopSendingFrame frame)
    {
        Listener listener = getListener();
        try
        {
            listener.onStopSending(this, frame);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    private void notifyMaxDataFrame(StreamMaxDataFrame frame)
    {
        Listener listener = getListener();
        try
        {
            listener.onMaxData(this, frame);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    @Override
    public String toString()
    {
        return "%s[demand=%b]".formatted(super.toString(), hasDemand());
    }

    private class Sender implements Promise.Invocable<Stream>
    {
        private final AtomicMarkableReference<Invocable<Stream>> sendPromise = new AtomicMarkableReference<>(null, false);

        private boolean begin(boolean last, Invocable<Stream> promise)
        {
            return sendPromise.compareAndSet(null, promise, false, last);
        }

        @Override
        public void succeeded(Stream result)
        {
            boolean[] mark = new boolean[1];
            Invocable<Stream> promise;
            while (true)
            {
                promise = sendPromise.get(mark);
                boolean last = mark[0];
                if (sendPromise.compareAndSet(promise, null, last, last))
                    break;
            }
            if (mark[0])
            {
                if (updateCloseState(CloseState.LOCALLY_CLOSED))
                    removeAndNotifyClose();
            }
            promise.succeeded(result);
        }

        @Override
        public void failed(Throwable x)
        {
            boolean[] mark = new boolean[1];
            Invocable<Stream> promise;
            while (true)
            {
                promise = sendPromise.get(mark);
                boolean last = mark[0];
                if (sendPromise.compareAndSet(promise, null, last, true))
                    break;
            }
            if (updateCloseState(CloseState.CLOSED))
                removeAndNotifyClose();
            promise.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return sendPromise.get(new boolean[1]).getInvocationType();
        }
    }
}
