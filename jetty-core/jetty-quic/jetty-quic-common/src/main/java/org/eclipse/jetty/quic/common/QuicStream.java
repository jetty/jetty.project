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
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicMarkableReference;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.ResetFrame;
import org.eclipse.jetty.quic.api.frames.StopSendingFrame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.frames.FrameStream;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.util.AtomicBiInteger;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;

public class QuicStream extends AbstractStream
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicStream.class);

    private final AutoLock lock = new AutoLock();
    private final AtomicBiInteger framesInFlight = new AtomicBiInteger();
    private final Deque<Content.Chunk> dataQueue = new ArrayDeque<>(1);
    private final CloseState closeState = new CloseState();
    private final Sender sender = new Sender();
    private final AtomicLong recvOffset = new AtomicLong();
    private final AtomicLong recvMaxOffset = new AtomicLong();
    private final AtomicLong sentOffset = new AtomicLong();
    private final AtomicLong sentMaxOffset = new AtomicLong();
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final QuicSession session;
    private final FrameStream frameStream;
    private boolean readDemand;
    private boolean readStalled;

    public QuicStream(QuicSession session, long streamId, boolean local)
    {
        super(streamId, local);
        this.session = session;
        this.frameStream = new FrameStream.Stream(streamId, this::processDataFrame);
        this.readStalled = true;
    }

    @Override
    public boolean isTerminated()
    {
        try (var _ = lock.lock())
        {
            return closeState.isTerminated();
        }
    }

    @Override
    public boolean isLocallyClosed()
    {
        try (var _ = lock.lock())
        {
            return closeState.isLocallyClosed();
        }
    }

    @Override
    public boolean isRemotelyClosed()
    {
        try (var _ = lock.lock())
        {
            return closeState.isRemotelyClosed();
        }
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        super.setIdleTimeout(idleTimeout);
        session.scheduleTimeout(this);
    }

    void onStreamFrameSent(Frame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("tracked send of {} on {}", frame, this);
        framesInFlight.addAndGetLo(1);
    }

    void onStreamFrameAcknowledged(Frame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("tracked ack of {} on {}", frame, this);
        boolean last = switch (frame)
        {
            case ResetFrame _ -> true;
            case StreamFrame sf -> sf.isEndStream();
            default -> false;
        };
        while (true)
        {
            long encoded = framesInFlight.get();
            boolean wasClosed = AtomicBiInteger.getHi(encoded) != 0;
            boolean closed = wasClosed || last;
            int prevInFlight = AtomicBiInteger.getLo(encoded);
            int inFlight = prevInFlight - 1;
            if (framesInFlight.compareAndSet(encoded, closed ? 1 : 0, inFlight))
            {
                if (closed && inFlight == 0)
                {
                    boolean terminated;
                    try (var _ = lock.lock())
                    {
                        terminated = closeState.localComplete();
                    }
                    if (terminated)
                        session.remove(this);
                }
                break;
            }
        }
    }

    void onStreamFrameLost(Frame frame)
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
        boolean terminated = false;
        try (var _ = lock.lock())
        {
            chunk = dataQueue.poll();
            if (chunk == null)
                return null;

            if (Content.Chunk.isFailure(chunk, true))
                dataQueue.offer(chunk);
            else if (chunk.isLast())
                dataQueue.offer(Content.Chunk.EOF);

            if (chunk.isLast())
                terminated = closeState.remoteClose();
        }

        session.getFlowController().onDataRead(this, chunk.size());

        if (LOG.isDebugEnabled())
            LOG.debug("reading {} on {}", chunk, this);

        if (terminated)
            session.remove(this);
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
            processDataAvailable(true);
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
            try (var _ = lock.lock())
            {
                if (last && !closeState.localClosing())
                    throw new IllegalStateException("stream_locally_closed");
            }

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

    long getMaxData()
    {
        return session.getMaxData(this);
    }

    public long getRecvOffset()
    {
        return recvOffset.get();
    }

    void updateRecvOffset(long offset)
    {
        Atomics.updateMax(recvOffset, offset);
    }

    public long getRecvMaxOffset()
    {
        return recvMaxOffset.get();
    }

    boolean updateRecvMaxOffset(long offset)
    {
        return Atomics.updateMax(recvMaxOffset, offset);
    }

    public long getSendWindow()
    {
        return getSentMaxOffset() - getSentOffset();
    }

    public long getSentMaxOffset()
    {
        return sentMaxOffset.get();
    }

    public long getSentOffset()
    {
        return sentOffset.get();
    }

    public void updateSentOffset(long sent)
    {
        Atomics.updateMax(sentOffset, sent);
    }

    /// Updates the sent max data offset for this stream.
    ///
    /// This method is called initially when receiving the
    /// [TransportParameters.Ids#INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE],
    /// and later when receiving [StreamMaxDataFrame]s.
    public void updateSentMaxOffset(long maxData)
    {
        Atomics.updateMax(sentMaxOffset, maxData);
    }

    @Override
    public void reset(long appErrorCode, Promise.Invocable<Stream> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("resetting appError={} on {}", appErrorCode, this);

        // Remote unidirectional (receive-only) stream: cannot be reset.
        if (!isBidirectional() && !isLocal())
        {
            promise.failed(new UnsupportedOperationException("cannot reset remote unidirectional stream"));
            return;
        }

        boolean closing;
        try (var _ = lock.lock())
        {
            closing = closeState.localClosing();
        }
        if (closing)
        {
            session.reset(this, new ResetFrame(getId(), appErrorCode, -1), Promise.Invocable.from(promise, () ->
            {
                boolean terminated;
                try (var _ = lock.lock())
                {
                    terminated = closeState.localClose();
                }
                if (terminated)
                    session.remove(this);
            }));
        }
        else
        {
            promise.succeeded(this);
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
        if (disconnected.compareAndSet(false, true))
        {
            stopSending(appErrorCode, Promise.Invocable.from(NON_BLOCKING, (s, x) ->
            {
                if (x == null)
                    s.reset(appErrorCode, promise);
                else
                    promise.failed(x);
            }));
        }
        else
        {
            promise.succeeded(this);
        }
    }

    void onIdleTimeout(TimeoutException failure)
    {
        notifyIdleTimeout(failure, Promise.Invocable.from(NON_BLOCKING, (timeout, x) ->
        {
            boolean confirmed = x != null || timeout;

            if (LOG.isDebugEnabled())
                LOG.debug("idle timeout {} ms {} on {}", getIdleTimeout(), confirmed ? "confirmed" : "ignored", this);

            if (confirmed)
                disconnect(ErrorCode.NO_ERROR.code(), failure, Promise.Invocable.noop());
            else
                notIdle();
        }));
    }

    Invocable.Task processFrames(List<Frame.WithStreamId> frames)
    {
        // Frame processing does not need to go through SerializedInvoker,
        // because StreamFrames are naturally serialized by their offset.
        // If the Stream.Listener InvocationType is BLOCKING, frame tasks
        // may be executed out of order, but that is not different from
        // receiving them out of order from the network.
        return new FramesTask(frames);
    }

    /// Main entry point to process incoming frames received by [QuicSession].
    private void processFrame(Frame.WithStreamId frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", frame, this);

        notIdle();
        switch (frame)
        {
            case ResetFrame resetFrame -> frameStream.offer(resetFrame);
            case StopSendingFrame stopSendingFrame -> processStopSendingFrame(stopSendingFrame);
            case StreamDataBlockedFrame streamDataBlockedFrame -> notifyDataBlockedFrame(streamDataBlockedFrame);
            case StreamFrame streamFrame -> frameStream.offer(streamFrame);
            case StreamMaxDataFrame streamMaxDataFrame -> notifyMaxDataFrame(streamMaxDataFrame);
        }
    }

    private void processDataFrame(Frame.WithOffset frame)
    {
        switch (frame)
        {
            case ResetFrame resetFrame -> processResetFrame(resetFrame);
            case StreamFrame streamFrame -> processStreamFrame(streamFrame);
            default -> throw new AssertionError("unexpected_frame");
        }
    }

    private void processStopSendingFrame(StopSendingFrame frame)
    {
        notifyStopSendingFrame(frame);
        // RFC-9000[3.5]: receiving a STOP_SENDING requires sending a RESET_STREAM.
        reset(frame.applicationErrorCode(), Promise.Invocable.noop());
    }

    private void processResetFrame(ResetFrame resetFrame)
    {
        boolean process;
        try (var _ = lock.lock())
        {
            if (!closeState.remoteClosing())
                return;
            process = dataQueue.isEmpty() && readDemand;
            dataQueue.offer(Content.Chunk.from(new EofException("reset"), true));
        }
        if (process)
            processDataAvailable(false);
    }

    private void processStreamFrame(StreamFrame frame)
    {
        boolean process;
        try (var _ = lock.lock())
        {
            process = dataQueue.isEmpty() && readDemand;
            boolean last = frame.isEndStream();
            Content.Chunk chunk = frame.map(data -> Content.Chunk.from(data, last));
            // Retain the chunk because it is stored for later use.
            chunk.retain();
            dataQueue.offer(chunk);
            if (last && !closeState.remoteClosing())
                throw new IllegalStateException("stream_remotely_closed");
            if (LOG.isDebugEnabled())
                LOG.debug("offer data notify={} {} on {}", process, chunk, this);
        }

        if (process)
            processDataAvailable(false);
    }

    private void processDataAvailable(boolean immediate)
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
            if (LOG.isDebugEnabled())
                LOG.debug("notifying data available on {}", this);
            notifyDataAvailable(immediate);
        }
    }

    void processFailure(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing failure on {}", this, failure);

        boolean process;
        try (var _ = lock.lock())
        {
            if (!closeState.remoteClosing())
                return;
            process = dataQueue.isEmpty() && readDemand;
            dataQueue.offer(Content.Chunk.from(failure, true));
        }

        if (process)
            processDataAvailable(false);
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

    private void notifyIdleTimeout(TimeoutException failure, Promise.Invocable<Boolean> promise)
    {
        Listener listener = getListener();
        try
        {
            listener.onIdleTimeout(this, failure, promise);
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
        try (var l = lock.tryLock())
        {
            String held = l.isHeldByCurrentThread() ? "" : "?";
            return "%s[%s:%s,dataQueue=%d,demand=%b,data/max=%d/%d]".formatted(super.toString(), held, closeState, dataQueue.size(), readDemand, getSentOffset(), getSentMaxOffset());
        }
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
                boolean terminated;
                try (var _ = lock.lock())
                {
                    terminated = closeState.localClose();
                }
                if (terminated)
                    session.remove(QuicStream.this);
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
            boolean terminated;
            try (var _ = lock.lock())
            {
                terminated = closeState.localClose();
            }
            if (terminated)
                session.remove(QuicStream.this);
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
        private static final int REMOTELY_CLOSING = 0x08;
        private static final int REMOTELY_CLOSED = 0x10;
        private static final int REMOTELY_TERMINATED = REMOTELY_CLOSING | REMOTELY_CLOSED;
        private static final int TERMINATED = LOCALLY_TERMINATED | REMOTELY_TERMINATED;

        private int state;

        /// @return whether the local closing was initiated
        private boolean localClosing()
        {
            int previous = state;
            int current = state = previous | LOCALLY_CLOSING;
            if (LOG.isDebugEnabled())
                LOG.debug("update {} -> {} on {}", asString(previous), asString(current), QuicStream.this);
            return (previous & LOCALLY_CLOSING) == 0;
        }

        /// @return whether the local close was terminal.
        private boolean localClose()
        {
            return update(LOCALLY_CLOSED);
        }

        /// @return whether the local complete was terminal.
        private boolean localComplete()
        {
            return update(LOCALLY_COMPLETE);
        }

        /// @return whether the remote closing was initiated
        private boolean remoteClosing()
        {
            int previous = state;
            int current = state = previous | REMOTELY_CLOSING;
            if (LOG.isDebugEnabled())
                LOG.debug("update {} -> {} on {}", asString(previous), asString(current), QuicStream.this);
            return (previous & REMOTELY_CLOSING) == 0;
        }

        private boolean remoteClose()
        {
            return update(REMOTELY_CLOSED);
        }

        private boolean update(int event)
        {
            int previous = state;
            int current = state = previous | event;
            if (LOG.isDebugEnabled())
                LOG.debug("update {} -> {} on {}", asString(previous), asString(current), QuicStream.this);
            return !isTerminal(previous) && isTerminal(current);
        }

        private boolean isLocallyClosed()
        {
            return (state & LOCALLY_CLOSED) != 0;
        }

        private boolean isRemotelyClosed()
        {
            return (state & REMOTELY_CLOSED) != 0;
        }

        private boolean isTerminated()
        {
            return isTerminal(state);
        }

        private boolean isTerminal(int current)
        {
            return current == TERMINATED;
        }

        private String asString(int state)
        {
            String local = switch (state & LOCALLY_TERMINATED)
            {
                case LOCALLY_CLOSING -> "CLOSING";
                case LOCALLY_CLOSED, LOCALLY_CLOSING | LOCALLY_CLOSED -> "CLOSED";
                case LOCALLY_COMPLETE, LOCALLY_CLOSING | LOCALLY_COMPLETE -> "COMPLETE";
                case LOCALLY_CLOSED | LOCALLY_COMPLETE, LOCALLY_TERMINATED -> "TERMINATED";
                default -> "OPEN";
            };
            String remote = switch (state & REMOTELY_TERMINATED)
            {
                case REMOTELY_CLOSING -> "CLOSING";
                case REMOTELY_CLOSED, REMOTELY_TERMINATED -> "CLOSED";
                default -> "OPEN";
            };
            return "%s@%x[local=%s,remote=%s]".formatted(getClass().getSimpleName(), hashCode(), local, remote);
        }

        @Override
        public String toString()
        {
            return asString(state);
        }
    }

    private class FramesTask implements Invocable.Task
    {
        private final List<Frame.WithStreamId> frames;

        private FramesTask(List<Frame.WithStreamId> frames)
        {
            this.frames = frames;
        }

        @Override
        public void run()
        {
            try
            {
                for (Frame.WithStreamId frame : frames)
                {
                    processFrame(frame);
                }
            }
            catch (Throwable x)
            {
                session.fail(x);
            }
        }

        @Override
        public InvocationType getInvocationType()
        {
            Listener listener = getListener();
            return listener == null ? NON_BLOCKING : listener.getInvocationType();
        }

        @Override
        public String toString()
        {
            return "%s@%x%s".formatted(TypeUtil.toShortName(getClass()), hashCode(), frames);
        }
    }
}
