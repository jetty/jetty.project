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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicMarkableReference;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.ResetStreamFrame;
import org.eclipse.jetty.quic.api.frames.StopSendingFrame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.frames.FrameStream;
import org.eclipse.jetty.util.AtomicBiInteger;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.Callback;
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
    private final AtomicLong sendMaxOffset = new AtomicLong();
    private final AtomicBoolean flowControlStalled = new AtomicBoolean();
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
            case ResetStreamFrame _ -> true;
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
                        terminated = closeState.localAcked();
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

        return chunk;
    }

    @Override
    public void demand()
    {
        boolean process = false;
        try (var _ = lock.lock())
        {
            readDemand = true;

            // Field readStalled prevents infinite recursion in case
            // that demand() is called when there is data to read().
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
    public void data(boolean last, RetainableByteBuffer data, Callback callback)
    {
        try
        {
            // Avoid infinite buffering in the session flusher.
            if (!sender.begin(last, callback))
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
            callback.failed(x);
        }
    }

    @Override
    public void maxData(long maxData, Callback callback)
    {
        session.maxData(this, new StreamMaxDataFrame(getId(), maxData), callback);
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
        return getSendMaxOffset() - getSentOffset();
    }

    public long getSendMaxOffset()
    {
        return sendMaxOffset.get();
    }

    public long getSentOffset()
    {
        return sentOffset.get();
    }

    public void updateSentOffset(long sent)
    {
        Atomics.updateMax(sentOffset, sent);
    }

    /// Updates the send max data offset for this stream.
    ///
    /// This method is called initially when receiving the
    /// [TransportParameters.Ids#INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE],
    /// and later when receiving [StreamMaxDataFrame]s.
    public void updateSendMaxOffset(long maxData)
    {
        if (Atomics.updateMax(sendMaxOffset, maxData))
            flowControlStalled.set(false);
    }

    public boolean stallFlowControl()
    {
        return flowControlStalled.compareAndSet(false, true);
    }

    @Override
    public void reset(long appErrorCode, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("resetting appError={} on {}", appErrorCode, this);

        // Remote unidirectional (receive-only) stream: cannot be reset.
        if (!isBidirectional() && !isLocal())
        {
            callback.failed(new UnsupportedOperationException("cannot reset remote unidirectional stream"));
            return;
        }

        boolean closing;
        try (var _ = lock.lock())
        {
            closing = closeState.localClosing();
        }
        if (closing)
        {
            session.reset(this, new ResetStreamFrame(getId(), appErrorCode, -1), Callback.from(callback, () ->
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
            callback.succeeded();
        }
    }

    @Override
    public void stopSending(long appErrorCode, Callback callback)
    {
        session.stopSending(this, new StopSendingFrame(getId(), appErrorCode), callback);
    }

    @Override
    public void disconnect(long appErrorCode, Throwable failure, Callback callback)
    {
        if (disconnected.compareAndSet(false, true))
        {
            stopSending(appErrorCode, Callback.from(callback.getInvocationType(), x ->
            {
                if (x == null)
                    reset(appErrorCode, callback);
                else
                    callback.failed(x);
            }));
        }
        else
        {
            callback.succeeded();
        }
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

        switch (frame)
        {
            case ResetStreamFrame resetStreamFrame ->
            {
                session.checkRateControl(frame);
                frameStream.offer(resetStreamFrame);
            }
            case StopSendingFrame stopSendingFrame ->
            {
                session.checkRateControl(frame);
                processStopSendingFrame(stopSendingFrame);
            }
            case StreamDataBlockedFrame streamDataBlockedFrame ->
            {
                session.checkRateControl(frame);
                notifyDataBlockedFrame(streamDataBlockedFrame);
            }
            case StreamFrame streamFrame ->
            {
                if (streamFrame.length() == 0)
                    session.checkRateControl(frame);
                frameStream.offer(streamFrame);
            }
            case StreamMaxDataFrame streamMaxDataFrame ->
            {
                if (streamMaxDataFrame.maxData() <= getSendMaxOffset())
                    session.checkRateControl(frame);
                notifyMaxDataFrame(streamMaxDataFrame);
            }
        }
    }

    private void processDataFrame(Frame.WithOffset frame)
    {
        switch (frame)
        {
            case ResetStreamFrame resetStreamFrame -> processResetStreamFrame(resetStreamFrame);
            case StreamFrame streamFrame -> processStreamFrame(streamFrame);
            default -> throw new AssertionError("unexpected_frame");
        }
    }

    private void processStopSendingFrame(StopSendingFrame frame)
    {
        notifyStopSendingFrame(frame);
        // RFC-9000 #3.5: receiving a STOP_SENDING requires sending a RESET_STREAM.
        reset(frame.applicationErrorCode(), Callback.NOOP);
    }

    private void processResetStreamFrame(ResetStreamFrame resetStreamFrame)
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

    Invocable.Task processFailure(Throwable failure)
    {
        return new Invocable.Task.Abstract(getInvocationType())
        {
            @Override
            public void run()
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("processing failure on {}", QuicStream.this, failure);

                boolean process;
                List<Content.Chunk> chunks;
                try (var _ = lock.lock())
                {
                    // Notify the application of the failure.
                    process = dataQueue.isEmpty() && readDemand;

                    // The chunks must be released to avoid leaking buffers.
                    chunks = List.copyOf(dataQueue);
                    dataQueue.clear();
                    dataQueue.offer(Content.Chunk.from(failure, true));
                }
                chunks.forEach(Content.Chunk::release);

                if (process)
                    processDataAvailable(false);

                session.remove(QuicStream.this);
            }
        };
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

    private Invocable.InvocationType getInvocationType()
    {
        Listener listener = getListener();
        return listener != null ? listener.getInvocationType() : NON_BLOCKING;
    }

    @Override
    public String toString()
    {
        try (var l = lock.tryLock())
        {
            String held = l.isHeldByCurrentThread() ? "" : "?";
            return "%s[%s:%s,dataQueue=%d,demand=%b,data/max=%d/%d]".formatted(super.toString(), held, closeState, dataQueue.size(), readDemand, getSentOffset(), getSendMaxOffset());
        }
    }

    private class Sender implements Callback
    {
        private final AtomicMarkableReference<Callback> sendCallback = new AtomicMarkableReference<>(null, false);

        private boolean begin(boolean last, Callback callback)
        {
            return sendCallback.compareAndSet(null, callback, false, last);
        }

        @Override
        public void succeeded()
        {
            boolean[] mark = new boolean[1];
            Callback callback;
            while (true)
            {
                callback = sendCallback.get(mark);
                boolean last = mark[0];
                if (sendCallback.compareAndSet(callback, null, last, last))
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
            callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            boolean[] mark = new boolean[1];
            Callback callback;
            while (true)
            {
                callback = sendCallback.get(mark);
                boolean last = mark[0];
                if (sendCallback.compareAndSet(callback, null, last, true))
                    break;
            }
            boolean terminated;
            try (var _ = lock.lock())
            {
                terminated = closeState.localClose();
            }
            if (terminated)
                session.remove(QuicStream.this);
            callback.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            Callback callback = sendCallback.get(new boolean[1]);
            return Invocable.getInvocationType(callback);
        }
    }

    private class CloseState
    {
        /// State that indicates that a local close is initiated by sending a last data frame or a reset.
        private static final int LOCALLY_CLOSING = 0x01;
        /// State that indicates that a previously initiated local close is complete.
        private static final int LOCALLY_CLOSED = 0x02;
        /// State that indicates that a local close has been acknowledged.
        private static final int LOCALLY_ACKED = 0x04;
        /// Mask that indicates that a local close has been initiated, completed and acknowledged.
        private static final int LOCALLY_TERMINATED = LOCALLY_CLOSING | LOCALLY_CLOSED | LOCALLY_ACKED;
        /// State that indicates that a remote close is initiated by receiving a last data frame or a reset.
        private static final int REMOTELY_CLOSING = 0x08;
        /// State that indicates that a previously initiated remote close has been completed by reading it.
        private static final int REMOTELY_CLOSED = 0x10;
        /// Mask that indicates that a remote close has been initiated and completed.
        private static final int REMOTELY_TERMINATED = REMOTELY_CLOSING | REMOTELY_CLOSED;
        /// Mask that indicates that both local close and remote close have been terminated.
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

        /// @return whether the local acknowledgement was terminal.
        private boolean localAcked()
        {
            return update(LOCALLY_ACKED);
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
                case LOCALLY_ACKED, LOCALLY_CLOSING | LOCALLY_ACKED -> "COMPLETE";
                case LOCALLY_CLOSED | LOCALLY_ACKED, LOCALLY_TERMINATED -> "TERMINATED";
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
                session.fail(x, false);
            }
        }

        @Override
        public InvocationType getInvocationType()
        {
            return QuicStream.this.getInvocationType();
        }

        @Override
        public String toString()
        {
            return "%s@%x%s".formatted(TypeUtil.toShortName(getClass()), hashCode(), frames);
        }
    }
}
