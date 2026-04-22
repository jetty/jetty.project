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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicMarkableReference;

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
import org.eclipse.jetty.util.AtomicBiInteger;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicStream extends AbstractStream
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicStream.class);

    private final AutoLock lock = new AutoLock();
    private final AtomicBiInteger framesInFlight = new AtomicBiInteger();
    private final FrameStream frameStream = new FrameStream(this::processDataFrame);
    private final Deque<Content.Chunk> dataQueue = new ArrayDeque<>(1);
    private final CloseState closeState = new CloseState();
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
        return closeState.isTerminated();
    }

    @Override
    public boolean isLocallyClosed()
    {
        return closeState.isLocallyClosed();
    }

    @Override
    public boolean isRemotelyClosed()
    {
        return closeState.isRemotelyClosed();
    }

    void onStreamFrameSent(StreamFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("tracked send of {} on {}", frame, this);
        framesInFlight.addAndGetLo(1);
    }

    void onStreamFrameAcknowledged(StreamFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("tracked ack of {} on {}", frame, this);
        while (true)
        {
            long encoded = framesInFlight.get();
            boolean wasClosed = AtomicBiInteger.getHi(encoded) != 0;
            boolean closed = wasClosed || frame.isEndStream();
            int prevInFlight = AtomicBiInteger.getLo(encoded);
            int inFlight = prevInFlight - 1;
            if (framesInFlight.compareAndSet(encoded, closed ? 1 : 0, inFlight))
            {
                if (closed && inFlight == 0)
                {
                    if (closeState.localComplete())
                        removeAndNotifyClose();
                }
                break;
            }
        }
    }

    void onStreamFrameLost(StreamFrame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("tracked loss of {} on {}", frame, this);
        framesInFlight.addAndGetLo(-1);
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

        boolean terminated = false;
        if (chunk.isLast())
            terminated = closeState.remoteClose();

        if (terminated)
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
            if (last && !closeState.initLocalClose())
                throw new IllegalStateException("stream_locally_closed");

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
        return getSendMaxData() - getSendData();
    }

    private long getSendMaxData()
    {
        return sendMaxData.get();
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
        if (closeState.initLocalClose())
        {
            session.reset(this, new ResetFrame(getId(), appErrorCode, sendData.get()), Promise.Invocable.from(promise, () ->
            {
                if (closeState.localClose())
                    removeAndNotifyClose();
            }));
        }
        else
        {
            promise.failed(new IllegalStateException("stream_locally_closed"));
        }
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
            case ResetFrame resetFrame ->
            {
                // TODO: frameStream.reset(resetFrame);
            }
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
        boolean terminated = closeState.remoteClose();
        // TODO: queue a failure chunk, and notify through that, rather than onReset()?
        notifyResetFrame(resetFrame);
        if (terminated)
            removeAndNotifyClose();
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
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    private void processStreamFrame(StreamFrame frame)
    {
        boolean process;
        try (var _ = lock.lock())
        {
            process = dataQueue.isEmpty() && readDemand;
            Content.Chunk chunk = frame.map(data -> Content.Chunk.from(data, frame.isEndStream()));
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
        return "%s[%s,demand=%b,data/max=%d/%d]".formatted(super.toString(), closeState, hasDemand(), getSendData(), getSendMaxData());
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
                if (closeState.localClose())
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
            if (closeState.fail())
                removeAndNotifyClose();
            promise.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return sendPromise.get(new boolean[1]).getInvocationType();
        }
    }

    private class CloseState
    {
        private static final int LOCALLY_CLOSING = 0x01;
        private static final int LOCALLY_CLOSED = 0x02;
        private static final int LOCALLY_COMPLETE = 0x04;
        private static final int LOCALLY_TERMINATED = LOCALLY_CLOSING | LOCALLY_CLOSED | LOCALLY_COMPLETE;
        private static final int REMOTELY_CLOSED = 0x08;
        private static final int FAILED = 0x10;
        private static final int TERMINATED = LOCALLY_TERMINATED | REMOTELY_CLOSED;

        private final AtomicInteger state = new AtomicInteger();

        /// @return whether the local close was initiated
        private boolean initLocalClose()
        {
            int previous = state.getAndUpdate(s -> s | LOCALLY_CLOSING);
            int current = previous | LOCALLY_CLOSING;
            if (LOG.isDebugEnabled())
                LOG.debug("update {} -> {} on {}", asString(previous), asString(current), QuicStream.this);
            return (previous & (LOCALLY_CLOSING | FAILED)) == 0;
        }

        /// @return whether the local close was terminal.
        private boolean localClose()
        {
            return update(LOCALLY_CLOSED);
        }

        /// @return whether the local complete was terminal.
        public boolean localComplete()
        {
            return update(LOCALLY_COMPLETE);
        }

        private boolean remoteClose()
        {
            return update(REMOTELY_CLOSED);
        }

        private boolean update(int event)
        {
            int previous = state.getAndUpdate(s -> s | event);
            int current = previous | event;
            if (LOG.isDebugEnabled())
                LOG.debug("update {} -> {} on {}", asString(previous), asString(current), QuicStream.this);
            return !isTerminal(previous) && isTerminal(current);
        }

        private boolean isLocallyClosed()
        {
            return (state.get() & (LOCALLY_CLOSED | FAILED)) != 0;
        }

        private boolean isRemotelyClosed()
        {
            return (state.get() & (REMOTELY_CLOSED | FAILED)) != 0;
        }

        private boolean isTerminated()
        {
            return isTerminal(state.get());
        }

        public boolean fail()
        {
            int previous = state.getAndUpdate(s -> s | FAILED);
            return !isTerminal(previous);
        }

        private static boolean isTerminal(int current)
        {
            return current == TERMINATED || (current & FAILED) != 0;
        }

        private static String asString(int state)
        {
            String local = switch (state & LOCALLY_TERMINATED)
            {
                case LOCALLY_CLOSING -> "CLOSING";
                case LOCALLY_CLOSED, LOCALLY_CLOSING | LOCALLY_CLOSED -> "CLOSED";
                case LOCALLY_COMPLETE, LOCALLY_CLOSING | LOCALLY_COMPLETE -> "COMPLETE";
                case LOCALLY_CLOSED | LOCALLY_COMPLETE, LOCALLY_TERMINATED -> "TERMINATED";
                default -> "OPEN";
            };
            String remote = (state & REMOTELY_CLOSED) == 0 ? "OPEN" : "CLOSED";
            return "CloseState[local=%s,remote=%s,failed=%b]".formatted(local, remote, (state & FAILED) != 0);
        }

        @Override
        public String toString()
        {
            return asString(state.get());
        }
    }
}
