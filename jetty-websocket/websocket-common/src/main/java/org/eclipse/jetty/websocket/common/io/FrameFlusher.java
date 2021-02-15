//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.io;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;

public class FrameFlusher extends IteratingCallback
{
    public static final BinaryFrame FLUSH_FRAME = new BinaryFrame();
    private static final Logger LOG = Log.getLogger(FrameFlusher.class);

    private final ByteBufferPool bufferPool;
    private final EndPoint endPoint;
    private final int bufferSize;
    private final Generator generator;
    private final int maxGather;
    private final Deque<FrameEntry> queue = new ArrayDeque<>();
    private final List<FrameEntry> entries;
    private final List<ByteBuffer> buffers;

    // Stats (where a message is defined as a WebSocket frame)
    private final LongAdder messagesOut = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();

    private boolean closed;
    private boolean canEnqueue = true;
    private Throwable terminated;
    private ByteBuffer aggregate;
    private BatchMode batchMode;

    public FrameFlusher(ByteBufferPool bufferPool, Generator generator, EndPoint endPoint, int bufferSize, int maxGather)
    {
        this.bufferPool = bufferPool;
        this.endPoint = endPoint;
        this.bufferSize = bufferSize;
        this.generator = Objects.requireNonNull(generator);
        this.maxGather = maxGather;
        this.entries = new ArrayList<>(maxGather);
        this.buffers = new ArrayList<>((maxGather * 2) + 1);
    }

    public boolean enqueue(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        FrameEntry entry = new FrameEntry(frame, callback, batchMode);

        Throwable dead;

        synchronized (this)
        {
            if (canEnqueue)
            {
                dead = terminated;
                if (dead == null)
                {
                    byte opCode = frame.getOpCode();
                    if (opCode == OpCode.PING || opCode == OpCode.PONG)
                    {
                        queue.offerFirst(entry);
                    }
                    else
                    {
                        queue.offerLast(entry);
                    }

                    if (opCode == OpCode.CLOSE)
                    {
                        this.canEnqueue = false;
                    }
                }
            }
            else
            {
                dead = new ClosedChannelException();
            }
        }

        if (dead == null)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Enqueued {} to {}", entry, this);
            }
            return true;
        }

        notifyCallbackFailure(callback, dead);
        return false;
    }

    @Override
    protected Action process() throws Throwable
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Flushing {}", this);

        int space = aggregate == null ? bufferSize : BufferUtil.space(aggregate);
        BatchMode currentBatchMode = BatchMode.AUTO;
        synchronized (this)
        {
            if (closed)
            {
                return Action.SUCCEEDED;
            }

            if (terminated != null)
            {
                throw terminated;
            }

            while (!queue.isEmpty() && entries.size() < maxGather)
            {
                FrameEntry entry = queue.poll();
                currentBatchMode = BatchMode.max(currentBatchMode, entry.batchMode);
                messagesOut.increment();

                // Force flush if we need to.
                if (entry.frame == FLUSH_FRAME)
                    currentBatchMode = BatchMode.OFF;

                int payloadLength = BufferUtil.length(entry.frame.getPayload());
                int approxFrameLength = Generator.MAX_HEADER_LENGTH + payloadLength;

                // If it is a "big" frame, avoid copying into the aggregate buffer.
                if (approxFrameLength > (bufferSize >> 2))
                    currentBatchMode = BatchMode.OFF;

                // If the aggregate buffer overflows, do not batch.
                space -= approxFrameLength;
                if (space <= 0)
                    currentBatchMode = BatchMode.OFF;

                entries.add(entry);
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} processing {} entries: {}", this, entries.size(), entries);

        if (entries.isEmpty())
        {
            if (batchMode != BatchMode.AUTO)
            {
                // Nothing more to do, release the aggregate buffer if we need to.
                // Releasing it here rather than in succeeded() allows for its reuse.
                releaseAggregate();
                return Action.IDLE;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("{} auto flushing", this);

            return flush();
        }

        batchMode = currentBatchMode;

        return currentBatchMode == BatchMode.OFF ? flush() : batch();
    }

    private Action batch()
    {
        if (aggregate == null)
        {
            aggregate = bufferPool.acquire(bufferSize, true);
            if (LOG.isDebugEnabled())
                LOG.debug("{} acquired aggregate buffer {}", this, aggregate);
        }

        for (FrameEntry entry : entries)
        {
            entry.generateHeaderBytes(aggregate);

            ByteBuffer payload = entry.frame.getPayload();
            if (BufferUtil.hasContent(payload))
                BufferUtil.append(aggregate, payload);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("{} aggregated {} frames: {}", this, entries.size(), entries);

        // We just aggregated the entries, so we need to succeed their callbacks.
        succeeded();

        return Action.SCHEDULED;
    }

    private Action flush()
    {
        if (!BufferUtil.isEmpty(aggregate))
        {
            buffers.add(aggregate);
            if (LOG.isDebugEnabled())
                LOG.debug("{} flushing aggregate {}", this, aggregate);
        }

        for (FrameEntry entry : entries)
        {
            // Skip the "synthetic" frame used for flushing.
            if (entry.frame == FLUSH_FRAME)
                continue;

            buffers.add(entry.generateHeaderBytes());
            ByteBuffer payload = entry.frame.getPayload();
            if (BufferUtil.hasContent(payload))
                buffers.add(payload);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} flushing {} frames: {}", this, entries.size(), entries);

        if (buffers.isEmpty())
        {
            releaseAggregate();
            // We may have the FLUSH_FRAME to notify.
            succeedEntries();
            return Action.IDLE;
        }

        int i = 0;
        int bytes = 0;
        ByteBuffer[] bufferArray = new ByteBuffer[buffers.size()];
        for (ByteBuffer bb : buffers)
        {
            bytes += bb.limit() - bb.position();
            bufferArray[i++] = bb;
        }
        bytesOut.add(bytes);

        endPoint.write(this, buffers.toArray(new ByteBuffer[buffers.size()]));
        buffers.clear();
        return Action.SCHEDULED;
    }

    private int getQueueSize()
    {
        synchronized (this)
        {
            return queue.size();
        }
    }

    @Override
    public void succeeded()
    {
        succeedEntries();
        super.succeeded();
    }

    private void succeedEntries()
    {
        for (FrameEntry entry : entries)
        {
            notifyCallbackSuccess(entry.callback);
            entry.release();
            if (entry.frame.getOpCode() == OpCode.CLOSE)
            {
                synchronized (this)
                {
                    // we know that enqueue protects us.
                    // and the processing will not contain extra frame entries.
                    closed = true;
                }
                endPoint.shutdownOutput();
            }
        }
        entries.clear();
    }

    @Override
    public void onCompleteFailure(Throwable failure)
    {
        releaseAggregate();

        synchronized (this)
        {
            if (terminated == null)
            {
                terminated = failure;
                if (LOG.isDebugEnabled())
                    LOG.debug("Write flush failure", failure);
            }
            entries.addAll(queue);
            queue.clear();
        }

        for (FrameEntry entry : entries)
        {
            notifyCallbackFailure(entry.callback, failure);
            entry.release();
        }
        entries.clear();
    }

    private void releaseAggregate()
    {
        if (BufferUtil.isEmpty(aggregate))
        {
            bufferPool.release(aggregate);
            aggregate = null;
        }
    }

    void terminate(Throwable cause)
    {
        Throwable reason;
        synchronized (this)
        {
            reason = terminated;
            if (reason == null)
                terminated = cause;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("{} {}", reason == null ? "Terminating" : "Terminated", this);
        if (reason == null)
            iterate();
    }

    protected void notifyCallbackSuccess(WriteCallback callback)
    {
        try
        {
            if (callback != null)
            {
                callback.writeSuccess();
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exception while notifying success of callback " + callback, x);
        }
    }

    protected void notifyCallbackFailure(WriteCallback callback, Throwable failure)
    {
        try
        {
            if (callback != null)
            {
                callback.writeFailed(failure);
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exception while notifying failure of callback " + callback, x);
        }
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
        int aggSize = -1;
        ByteBuffer agg = aggregate;
        if (agg != null)
            aggSize = agg.position();
        return String.format("%s[queueSize=%d,aggregateSize=%d,terminated=%s]",
            super.toString(),
            getQueueSize(),
            aggSize,
            terminated);
    }

    private class FrameEntry
    {
        private final Frame frame;
        private final WriteCallback callback;
        private final BatchMode batchMode;
        private ByteBuffer headerBuffer;

        private FrameEntry(Frame frame, WriteCallback callback, BatchMode batchMode)
        {
            this.frame = Objects.requireNonNull(frame);
            this.callback = callback;
            this.batchMode = batchMode;
        }

        private ByteBuffer generateHeaderBytes()
        {
            return headerBuffer = generator.generateHeaderBytes(frame);
        }

        private void generateHeaderBytes(ByteBuffer buffer)
        {
            generator.generateHeaderBytes(frame, buffer);
        }

        private void release()
        {
            if (headerBuffer != null)
            {
                generator.getBufferPool().release(headerBuffer);
                headerBuffer = null;
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s[%s,%s,%s,%s]", getClass().getSimpleName(), frame, callback, batchMode, terminated);
        }
    }
}
