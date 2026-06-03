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

package org.eclipse.jetty.websocket.core.internal;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.OutgoingEntry;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.core.exception.WebSocketWriteTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrameFlusher extends IteratingCallback
{
    public static final Frame FLUSH_FRAME = new Frame(OpCode.UNDEFINED)
    {
        @Override
        public boolean isControlFrame()
        {
            return true;
        }
    };

    private static final Logger LOG = LoggerFactory.getLogger(FrameFlusher.class);

    private final AutoLock _lock = new AutoLock();
    private final LongAdder _messagesOut = new LongAdder();
    private final LongAdder _bytesOut = new LongAdder();
    private final ByteBufferPool _bufferPool;
    private final EndPoint _endPoint;
    private final int _bufferSize;
    private final Generator _generator;
    private final int _maxGather;
    private final CyclicTimeouts<FlusherEntry> _cyclicTimeouts;
    private final Deque<FlusherEntry> _queue = new ArrayDeque<>();
    private final List<FlusherEntry> _currentEntries;
    private final List<FlusherEntry> _completedEntries = new ArrayList<>();
    private final List<RetainableByteBuffer> _releasableBuffers = new ArrayList<>();
    private final Behavior _behavior;
    private long _currentMessageExpiry = Long.MAX_VALUE;

    private RetainableByteBuffer _batchBuffer;
    private boolean _canEnqueue = true;
    private Throwable _closedCause;
    private boolean _useDirectByteBuffers;

    public FrameFlusher(ByteBufferPool bufferPool, Scheduler scheduler, Generator generator, EndPoint endPoint, int bufferSize, int maxGather, Behavior behavior)
    {
        _behavior = behavior;
        _bufferPool = bufferPool;
        _endPoint = endPoint;
        _bufferSize = bufferSize;
        _generator = Objects.requireNonNull(generator);
        _maxGather = maxGather;
        _currentEntries = new ArrayList<>(maxGather);
        _cyclicTimeouts = new CyclicTimeouts<>(scheduler)
        {
            private boolean _expired = false;

            @Override
            protected boolean onExpired(FlusherEntry expirable)
            {
                // This is called with lock held so we delay the abort until we exit the lock in iterate().
                _expired = true;
                return false;
            }

            @Override
            protected Iterator<FlusherEntry> iterator()
            {
                return TypeUtil.concat(_currentEntries.iterator(), _queue.iterator());
            }

            @Override
            protected void iterate()
            {
                // We need to acquire the lock before we can iterate over the queue and entries.
                try (AutoLock ignored = _lock.lock())
                {
                    super.iterate();
                }

                // Abort the flusher if any entries have timed out.
                if (_expired)
                    abort(new WebSocketWriteTimeoutException("FrameFlusher Write Timeout"));
            }
        };
    }

    public boolean isUseDirectByteBuffers()
    {
        return _useDirectByteBuffers;
    }

    public void setUseDirectByteBuffers(boolean useDirectByteBuffers)
    {
        this._useDirectByteBuffers = useDirectByteBuffers;
    }

    /**
     * Enqueue a Frame to be written to the endpoint.
     *
     * @param outgoingEntry the outgoing entry containing the frame, callback, etc.
     * @return returns true if the frame was enqueued and iterate needs to be called, returns false if the
     * FrameFlusher was closed
     */
    public boolean enqueue(OutgoingEntry outgoingEntry)
    {
        FlusherEntry entry = new FlusherEntry(outgoingEntry);
        Frame frame = outgoingEntry.getFrame();
        byte opCode = frame.getOpCode();

        Throwable error = null;
        boolean abort = false;
        List<FlusherEntry> failedEntries = null;
        CloseStatus closeStatus = null;

        try (AutoLock ignored = _lock.lock())
        {
            if (!_canEnqueue || _closedCause != null)
            {
                error = (_closedCause == null) ? new ClosedChannelException() : _closedCause;
            }
            else
            {
                switch (opCode)
                {
                    case OpCode.CLOSE:
                        closeStatus = CloseStatus.getCloseStatus(frame);
                        if (closeStatus.isAbnormal())
                        {
                            //fail all existing entries in the queue, and enqueue the error close
                            failedEntries = new ArrayList<>(_queue);
                            _queue.clear();
                        }
                        _queue.offerLast(entry);
                        this._canEnqueue = false;
                        break;

                    case OpCode.PING:
                    case OpCode.PONG:
                        _queue.offerFirst(entry);
                        break;

                    default:
                        if (entry.isExpired())
                        {
                            // For DATA frames there is a possibility that the message timeout has already expired in the
                            // case of a partial message. In this case do not even add it to the queue and abort the connection.
                            error = new WebSocketWriteTimeoutException("FrameFlusher Write Timeout");
                            abort = true;
                        }
                        else
                        {
                            _queue.offerLast(entry);
                        }
                        break;
                }

                if (!abort)
                    _cyclicTimeouts.schedule(entry);
            }
        }

        if (failedEntries != null)
        {
            WebSocketException failure =
                new WebSocketException(
                    "Flusher received abnormal CloseFrame: " +
                        CloseStatus.codeString(closeStatus.getCode()), closeStatus.getCause());

            for (FlusherEntry e : failedEntries)
            {
                notifyCallbackFailure(e.getCallback(), failure);
            }
        }

        if (error != null)
        {
            notifyCallbackFailure(outgoingEntry.getCallback(), error);
            if (abort)
                abort(error);
            return false;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Enqueued {} to {}", entry, this);
        return true;
    }

    public void onClose(Throwable cause)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _closedCause = cause == null ? new ClosedChannelException() : cause;
        }
        abort(_closedCause);
    }

    @Override
    protected Action process() throws Throwable
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Flushing {}", this);

        boolean flush = false;
        try (AutoLock ignored = _lock.lock())
        {
            if (_closedCause != null)
                throw _closedCause;

            while (!_queue.isEmpty() && _currentEntries.size() <= _maxGather)
            {
                FlusherEntry entry = _queue.poll();
                _currentEntries.add(entry);
                if (entry.getFrame() == FLUSH_FRAME)
                {
                    flush = true;
                    break;
                }
            }
        }

        // Process the entries outside the lock, as inside the ICB we only need the lock to modify _entries but not iterate it.
        boolean canBatch = true;
        List<ByteBuffer> buffers = new ArrayList<>((_maxGather * 2) + 1);
        if (_batchBuffer != null)
            buffers.add(_batchBuffer.getByteBuffer());
        for (FlusherEntry entry : _currentEntries)
        {
            if (entry.getFrame() == FLUSH_FRAME)
                continue;
            _messagesOut.increment();

            int batchSpace = _batchBuffer == null ? _bufferSize : BufferUtil.space(_batchBuffer.getByteBuffer());
            boolean batch = canBatch && entry.isBatch() &&
                !entry.getFrame().isControlFrame() &&
                entry.getFrame().getPayloadLength() < _bufferSize / 4 &&
                (batchSpace - Generator.MAX_HEADER_LENGTH) >= entry.getFrame().getPayloadLength();

            if (batch)
            {
                // Acquire a batchBuffer if we don't have one.
                if (_batchBuffer == null)
                {
                    _batchBuffer = acquireBuffer(_bufferSize);
                    buffers.add(_batchBuffer.getByteBuffer());
                }

                // Generate the frame into the batchBuffer.
                _generator.generateWholeFrame(entry.getFrame(), _batchBuffer.getByteBuffer());
            }
            else
            {
                if (canBatch && _batchBuffer != null && batchSpace >= Generator.MAX_HEADER_LENGTH)
                {
                    // Use the batch space for our header.
                    _generator.generateHeader(entry.getFrame(), _batchBuffer.getByteBuffer());
                }
                else
                {
                    // Add headers to the list of buffers.
                    RetainableByteBuffer headerBuffer = acquireBuffer(Generator.MAX_HEADER_LENGTH);
                    _releasableBuffers.add(headerBuffer);
                    _generator.generateHeader(entry.getFrame(), headerBuffer.getByteBuffer());
                    buffers.add(headerBuffer.getByteBuffer());
                }

                // Add the payload to the list of buffers.
                ByteBuffer payload = entry.getFrame().getPayload();
                if (BufferUtil.hasContent(payload))
                {
                    if (entry.getFrame().isMasked())
                    {
                        RetainableByteBuffer masked = acquireBuffer(entry.getFrame().getPayloadLength());
                        payload = masked.getByteBuffer();
                        _releasableBuffers.add(masked);
                        _generator.generatePayload(entry.getFrame(), payload);
                    }
                    buffers.add(payload);
                }

                // Once we have added another buffer we cannot add to the batch buffer again.
                canBatch = false;
                flush = true;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} processed {} entries flush={}: {}",
                this,
                _currentEntries.size(),
                flush,
                _currentEntries);

        if (flush)
        {
            // If we're flushing we should release the batch buffer upon completion.
            if (_batchBuffer != null)
            {
                _releasableBuffers.add(_batchBuffer);
                _batchBuffer = null;
            }

            int i = 0;
            int bytes = 0;
            ByteBuffer[] bufferArray = new ByteBuffer[buffers.size()];
            for (ByteBuffer bb : buffers)
            {
                bytes += bb.limit() - bb.position();
                bufferArray[i++] = bb;
            }
            _bytesOut.add(bytes);
            _endPoint.write(ReadableBuffer.wrap(bufferArray), this);
            buffers.clear();
        }
        else
        {
            // If we did not get any new entries go to IDLE state
            if (_currentEntries.isEmpty())
            {
                releaseAggregateIfEmpty();
                return Action.IDLE;
            }

            // We just aggregated the entries, so we need to succeed their callbacks.
            succeeded();
        }

        return Action.SCHEDULED;
    }

    private RetainableByteBuffer acquireBuffer(int capacity)
    {
        return _bufferPool.acquire(capacity, isUseDirectByteBuffers());
    }

    private int getQueueSize()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _queue.size();
        }
    }

    @Override
    protected void onSuccess()
    {
        try (AutoLock ignored = _lock.lock())
        {
            assert _completedEntries.isEmpty();
            _completedEntries.addAll(_currentEntries);
            _currentEntries.clear();
        }

        for (FlusherEntry entry : _completedEntries)
        {
            if (entry.getFrame().getOpCode() == OpCode.CLOSE && _behavior == Behavior.SERVER)
                _endPoint.shutdownOutput();
            notifyCallbackSuccess(entry.getCallback());
        }
        _completedEntries.clear();

        _releasableBuffers.forEach(RetainableByteBuffer::release);
        _releasableBuffers.clear();
        super.onSuccess();
    }

    @Override
    public void onCompleteFailure(Throwable failure)
    {
        if (_batchBuffer != null)
            _batchBuffer.clear();
        releaseAggregateIfEmpty();
        try (AutoLock ignored = _lock.lock())
        {
            // Ensure no more entries can be enqueued.
            _canEnqueue = false;
            if (_closedCause == null)
                _closedCause = failure;
            else if (_closedCause != failure)
                _closedCause.addSuppressed(failure);

            assert _completedEntries.isEmpty();
            _completedEntries.addAll(_queue);
            _completedEntries.addAll(_currentEntries);
            _queue.clear();
            _currentEntries.clear();
            _cyclicTimeouts.destroy();
        }

        for (FlusherEntry entry : _completedEntries)
        {
            notifyCallbackFailure(entry.getCallback(), failure);
        }
        _completedEntries.clear();

        _releasableBuffers.forEach(RetainableByteBuffer::release);
        _releasableBuffers.clear();
        _endPoint.close(_closedCause);
    }

    private void releaseAggregateIfEmpty()
    {
        if (_batchBuffer != null && !_batchBuffer.hasRemaining())
        {
            _batchBuffer.release();
            _batchBuffer = null;
        }
    }

    protected void notifyCallbackSuccess(Callback callback)
    {
        try
        {
            if (callback != null)
            {
                callback.succeeded();
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exception while notifying success of callback {}", callback, x);
        }
    }

    protected void notifyCallbackFailure(Callback callback, Throwable failure)
    {
        try
        {
            if (callback != null)
            {
                callback.failed(failure);
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exception while notifying failure of callback {}", callback, x);
        }
    }

    public long getMessagesOut()
    {
        return _messagesOut.longValue();
    }

    public long getBytesOut()
    {
        return _bytesOut.longValue();
    }

    @Override
    public String toString()
    {
        return String.format("%s[queueSize=%d,aggregate=%s]",
            super.toString(),
            getQueueSize(),
            _batchBuffer);
    }

    private class FlusherEntry implements CyclicTimeouts.Expirable
    {
        private final OutgoingEntry _outgoingEntry;
        private final long _expiry;

        public FlusherEntry(OutgoingEntry outgoingEntry)
        {
            _outgoingEntry = outgoingEntry;

            long messageTimeout = outgoingEntry.getMessageTimeout();
            long frameExpiry = CyclicTimeouts.Expirable.calcExpireNanoTime(outgoingEntry.getFrameTimeout());

            Frame frame = outgoingEntry.getFrame();
            if (frame.isDataFrame())
            {
                // If this is the first frame of the message, remember the message timeout.
                if (frame.getOpCode() != OpCode.CONTINUATION)
                    _currentMessageExpiry = CyclicTimeouts.Expirable.calcExpireNanoTime(messageTimeout);
                if (_currentMessageExpiry != Long.MAX_VALUE)
                    frameExpiry = (frameExpiry == Long.MAX_VALUE) ? _currentMessageExpiry : minNanoTime(frameExpiry, _currentMessageExpiry);
            }
            else
            {
                long messageExpiry = CyclicTimeouts.Expirable.calcExpireNanoTime(messageTimeout);
                if (messageTimeout != Long.MAX_VALUE)
                    frameExpiry = (frameExpiry == Long.MAX_VALUE) ? messageExpiry : minNanoTime(frameExpiry, messageExpiry);
            }

            _expiry = frameExpiry;
        }

        public Frame getFrame()
        {
            return _outgoingEntry.getFrame();
        }

        public Callback getCallback()
        {
            return _outgoingEntry.getCallback();
        }

        public boolean isBatch()
        {
            return _outgoingEntry.isBatch();
        }

        @Override
        public long getExpireNanoTime()
        {
            return _expiry;
        }

        public boolean isExpired()
        {
            return (_expiry != Long.MAX_VALUE) && NanoTime.until(_expiry) < 0;
        }

        private static long minNanoTime(long nanoTime1, long nanoTime2)
        {
            return NanoTime.isBeforeOrSame(nanoTime1, nanoTime2) ? nanoTime1 : nanoTime2;
        }

        @Override
        public String toString()
        {
            return String.format("%s{entry=%b,expire=%s}", TypeUtil.toShortName(getClass()), _outgoingEntry, NanoTime.millisUntil(_expiry));
        }
    }
}
