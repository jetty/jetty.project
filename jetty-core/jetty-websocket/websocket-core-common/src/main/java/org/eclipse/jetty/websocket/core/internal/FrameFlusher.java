//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.core.exception.WebSocketWriteTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrameFlusher extends IteratingCallback
{
    public static final Frame FLUSH_FRAME = new Frame(OpCode.BINARY);
    private static final Logger LOG = LoggerFactory.getLogger(FrameFlusher.class);

    private final AutoLock lock = new AutoLock();
    private final LongAdder messagesOut = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();
    private final ByteBufferPool bufferPool;
    private final EndPoint endPoint;
    private final int bufferSize;
    private final Generator generator;
    private final int maxGather;
    private final Deque<Entry> queue = new ArrayDeque<>();
    private final List<ByteBuffer> buffers;
    private final Scheduler timeoutScheduler;
    private final List<Entry> entries;
    private final List<Entry> previousEntries;
    private final List<Entry> failedEntries;

    private List<ByteBuffer> releasableBuffers = new ArrayList<>();
    private ByteBuffer batchBuffer;
    private boolean canEnqueue = true;
    private boolean flushed = true;
    private Throwable closedCause;
    private long idleTimeout;
    private boolean useDirectByteBuffers;

    public FrameFlusher(ByteBufferPool bufferPool, Scheduler scheduler, Generator generator, EndPoint endPoint, int bufferSize, int maxGather)
    {
        this.bufferPool = bufferPool;
        this.endPoint = endPoint;
        this.bufferSize = bufferSize;
        this.generator = Objects.requireNonNull(generator);
        this.maxGather = maxGather;
        this.entries = new ArrayList<>(maxGather);
        this.previousEntries = new ArrayList<>(maxGather);
        this.failedEntries = new ArrayList<>(maxGather);
        this.buffers = new ArrayList<>((maxGather * 2) + 1);
        this.timeoutScheduler = scheduler;
    }

    public boolean isUseDirectByteBuffers()
    {
        return useDirectByteBuffers;
    }

    public void setUseDirectByteBuffers(boolean useDirectByteBuffers)
    {
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    /**
     * Enqueue a Frame to be written to the endpoint.
     *
     * @param frame The frame to queue
     * @param callback The callback to call once the frame is sent
     * @param batch True if batch mode is to be used
     * @return returns true if the frame was enqueued and iterate needs to be called, returns false if the
     * FrameFlusher was closed
     */
    public boolean enqueue(Frame frame, Callback callback, boolean batch)
    {
        Entry entry = new Entry(frame, callback, batch);
        byte opCode = frame.getOpCode();

        Throwable dead;
        List<Entry> failedEntries = null;
        CloseStatus closeStatus = null;

        try (AutoLock l = lock.lock())
        {
            if (canEnqueue)
            {
                dead = closedCause;
                if (dead == null)
                {
                    switch (opCode)
                    {
                        case OpCode.CLOSE:
                            closeStatus = CloseStatus.getCloseStatus(frame);
                            if (closeStatus.isAbnormal())
                            {
                                //fail all existing entries in the queue, and enqueue the error close
                                failedEntries = new ArrayList<>(queue);
                                queue.clear();
                            }
                            queue.offerLast(entry);
                            this.canEnqueue = false;
                            break;

                        case OpCode.PING:
                        case OpCode.PONG:
                            queue.offerFirst(entry);
                            break;

                        default:
                            queue.offerLast(entry);
                            break;
                    }

                    /* If the queue was empty then no timeout has been set, so we set a timeout to check the current
                    entry when it expires. When the timeout expires we will go over entries in the queue and
                    entries list to see if any of them have expired, it will then reset the timeout for the frame
                    with the soonest expiry time. */
                    if ((idleTimeout > 0) && (queue.size() == 1) && entries.isEmpty())
                        timeoutScheduler.schedule(this::timeoutExpired, idleTimeout, TimeUnit.MILLISECONDS);
                }
            }
            else
            {
                dead = new ClosedChannelException();
            }
        }

        if (failedEntries != null)
        {
            WebSocketException failure =
                new WebSocketException(
                    "Flusher received abnormal CloseFrame: " +
                        CloseStatus.codeString(closeStatus.getCode()), closeStatus.getCause());

            for (Entry e : failedEntries)
            {
                notifyCallbackFailure(e.callback, failure);
            }
        }

        if (dead == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Enqueued {} to {}", entry, this);

            return true;
        }

        notifyCallbackFailure(callback, dead);
        return false;
    }

    public void onClose(Throwable cause)
    {
        try (AutoLock l = lock.lock())
        {
            // TODO: find a way to not create exception if cause is null.
            closedCause = cause == null ? new ClosedChannelException()
            {
                @Override
                public Throwable fillInStackTrace()
                {
                    return this;
                }
            } : cause;
        }
        iterate();
    }

    @Override
    protected Action process() throws Throwable
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Flushing {}", this);

        boolean flush = false;
        Callback releasingCallback = this;
        try (AutoLock l = lock.lock())
        {
            if (closedCause != null)
                throw closedCause;

            // Remember entries to succeed from previous process
            previousEntries.addAll(entries);
            entries.clear();

            if (flushed && batchBuffer != null)
                BufferUtil.clear(batchBuffer);

            while (!queue.isEmpty() && entries.size() <= maxGather)
            {
                Entry entry = queue.poll();
                entries.add(entry);
                if (entry.frame == FLUSH_FRAME)
                {
                    flush = true;
                    break;
                }

                messagesOut.increment();

                int batchSpace = batchBuffer == null ? bufferSize : BufferUtil.space(batchBuffer);

                boolean batch = entry.batch &&
                    !entry.frame.isControlFrame() &&
                    entry.frame.getPayloadLength() < bufferSize / 4 &&
                    (batchSpace - Generator.MAX_HEADER_LENGTH) >= entry.frame.getPayloadLength();

                if (batch)
                {
                    // Acquire a batchBuffer if we don't have one.
                    if (batchBuffer == null)
                    {
                        batchBuffer = acquireBuffer(bufferSize);
                        buffers.add(batchBuffer);
                    }

                    // Generate the frame into the batchBuffer.
                    generator.generateWholeFrame(entry.frame, batchBuffer);
                }
                else
                {
                    if (batchBuffer != null && batchSpace >= Generator.MAX_HEADER_LENGTH)
                    {
                        // Use the batch space for our header.
                        generator.generateHeader(entry.frame, batchBuffer);
                    }
                    else
                    {
                        // Add headers to the list of buffers.
                        ByteBuffer headerBuffer = acquireBuffer(Generator.MAX_HEADER_LENGTH);
                        releasableBuffers.add(headerBuffer);
                        generator.generateHeader(entry.frame, headerBuffer);
                        buffers.add(headerBuffer);
                    }

                    // Add the payload to the list of buffers.
                    ByteBuffer payload = entry.frame.getPayload();
                    if (BufferUtil.hasContent(payload))
                    {
                        if (entry.frame.isMasked())
                        {
                            payload = acquireBuffer(entry.frame.getPayloadLength());
                            releasableBuffers.add(payload);
                            generator.generatePayload(entry.frame, payload);
                        }

                        buffers.add(payload.slice());
                    }
                    flush = true;
                }

                flushed = flush;
            }

            // If we are going to flush we should release any buffers we have allocated after the callback completes.
            if (flush)
            {
                final List<ByteBuffer> callbackBuffers = releasableBuffers;
                releasableBuffers = new ArrayList<>();
                releasingCallback = Callback.from(releasingCallback, () ->
                {
                    for (ByteBuffer buffer : callbackBuffers)
                    {
                        bufferPool.release(buffer);
                    }
                });
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} processed {} entries flush={} batch={}: {}",
                this,
                entries.size(),
                flush,
                BufferUtil.toDetailString(batchBuffer),
                entries);

        // succeed previous entries
        for (Entry entry : previousEntries)
        {
            if (entry.frame.getOpCode() == OpCode.CLOSE)
                endPoint.shutdownOutput();
            notifyCallbackSuccess(entry.callback);
        }
        previousEntries.clear();

        // If we did not get any new entries go to IDLE state
        if (entries.isEmpty())
        {
            releaseAggregate();
            return Action.IDLE;
        }

        if (flush)
        {
            int i = 0;
            int bytes = 0;
            ByteBuffer[] bufferArray = new ByteBuffer[buffers.size()];
            for (ByteBuffer bb : buffers)
            {
                bytes += bb.limit() - bb.position();
                bufferArray[i++] = bb;
            }
            bytesOut.add(bytes);
            endPoint.write(releasingCallback, bufferArray);
            buffers.clear();
        }
        else
        {
            // We just aggregated the entries, so we need to succeed their callbacks.
            succeeded();
        }

        return Action.SCHEDULED;
    }

    private ByteBuffer acquireBuffer(int capacity)
    {
        return bufferPool.acquire(capacity, isUseDirectByteBuffers());
    }

    private int getQueueSize()
    {
        try (AutoLock l = lock.lock())
        {
            return queue.size();
        }
    }

    public void timeoutExpired()
    {
        boolean failed = false;
        try (AutoLock l = lock.lock())
        {
            if (closedCause != null)
                return;

            long currentTime = System.currentTimeMillis();
            long expiredIfCreatedBefore = currentTime - idleTimeout;
            long earliestEntry = currentTime;

            /* Iterate through entries in both the queue and entries list.
            If any entry has expired then we fail the FrameFlusher.
            Otherwise we will try to schedule a new timeout. */
            Iterator<Entry> iterator = TypeUtil.concat(entries.iterator(), queue.iterator());
            while (iterator.hasNext())
            {
                Entry entry = iterator.next();

                if (entry.getTimeOfCreation() <= expiredIfCreatedBefore)
                {
                    LOG.warn("FrameFlusher write timeout on entry: {}", entry);
                    failed = true;
                    canEnqueue = false;
                    closedCause = new WebSocketWriteTimeoutException("FrameFlusher Write Timeout");
                    failedEntries.addAll(entries);
                    failedEntries.addAll(queue);
                    entries.clear();
                    queue.clear();
                    break;
                }

                if (entry.getTimeOfCreation() < earliestEntry)
                    earliestEntry = entry.getTimeOfCreation();
            }

            // if a timeout is set schedule a new timeout if we haven't failed and still have entries
            if (!failed && idleTimeout > 0 && !(entries.isEmpty() && queue.isEmpty()))
            {
                long nextTimeout = earliestEntry + idleTimeout - currentTime;
                timeoutScheduler.schedule(this::timeoutExpired, nextTimeout, TimeUnit.MILLISECONDS);
            }
        }

        if (failed)
            this.iterate();
    }

    @Override
    public void onCompleteFailure(Throwable failure)
    {
        BufferUtil.clear(batchBuffer);
        releaseAggregate();
        try (AutoLock l = lock.lock())
        {
            failedEntries.addAll(queue);
            queue.clear();

            failedEntries.addAll(entries);
            entries.clear();

            for (ByteBuffer buffer : releasableBuffers)
            {
                bufferPool.release(buffer);
            }
            releasableBuffers.clear();

            if (closedCause == null)
                closedCause = failure;
            else if (closedCause != failure)
                closedCause.addSuppressed(failure);
        }

        for (Entry entry : failedEntries)
        {
            notifyCallbackFailure(entry.callback, failure);
        }

        failedEntries.clear();
        endPoint.close(closedCause);
    }

    private void releaseAggregate()
    {
        if (BufferUtil.isEmpty(batchBuffer))
        {
            bufferPool.release(batchBuffer);
            batchBuffer = null;
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

    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    public long getMessagesOut()
    {
        return messagesOut.longValue();
    }

    public long getBytesOut()
    {
        return bytesOut.longValue();
    }

    @Override
    public String toString()
    {
        return String.format("%s[queueSize=%d,aggregate=%s]",
            super.toString(),
            getQueueSize(),
            BufferUtil.toDetailString(batchBuffer));
    }

    private class Entry extends FrameEntry
    {
        private ByteBuffer headerBuffer;
        private final long timeOfCreation = System.currentTimeMillis();

        private Entry(Frame frame, Callback callback, boolean batch)
        {
            super(frame, callback, batch);
        }

        private long getTimeOfCreation()
        {
            return timeOfCreation;
        }

        @Override
        public String toString()
        {
            return String.format("%s{%s,%s,%b}", getClass().getSimpleName(), frame, callback, batch);
        }
    }
}
