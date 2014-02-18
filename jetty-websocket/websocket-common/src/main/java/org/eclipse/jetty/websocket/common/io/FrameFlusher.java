//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;

/**
 * Interface for working with bytes destined for {@link EndPoint#write(Callback, ByteBuffer...)}
 */
public class FrameFlusher
{
    public static final BinaryFrame FLUSH_FRAME = new BinaryFrame();
    private static final Logger LOG = Log.getLogger(FrameFlusher.class);

    private final ByteBufferPool bufferPool;
    private final EndPoint endpoint;
    private final int bufferSize;
    private final Generator generator;
    private final int maxGather;
    private final Object lock = new Object();
    private final ArrayQueue<FrameEntry> queue = new ArrayQueue<>(16, 16, lock);
    private final Flusher flusher = new Flusher();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Throwable failure;

    public FrameFlusher(ByteBufferPool bufferPool, Generator generator, EndPoint endpoint, int bufferSize, int maxGather)
    {
        this.bufferPool = bufferPool;
        this.endpoint = endpoint;
        this.bufferSize = bufferSize;
        this.generator = Objects.requireNonNull(generator);
        this.maxGather = maxGather;
    }

    public void enqueue(Frame frame, WriteCallback callback, OutgoingFrames.FlushMode flushMode)
    {
        if (closed.get())
        {
            notifyCallbackFailure(callback, new EOFException("Connection has been closed locally"));
            return;
        }
        if (flusher.isFailed())
        {
            notifyCallbackFailure(callback, failure);
            return;
        }

        FrameEntry entry = new FrameEntry(frame, callback, flushMode);

        synchronized (lock)
        {
            switch (frame.getOpCode())
            {
                case OpCode.PING:
                {
                    // Prepend PINGs so they are processed first.
                    queue.add(0, entry);
                    break;
                }
                case OpCode.CLOSE:
                {
                    // There may be a chance that other frames are
                    // added after this close frame, but we will
                    // fail them later to keep it simple here.
                    closed.set(true);
                    queue.add(entry);
                    break;
                }
                default:
                {
                    queue.add(entry);
                    break;
                }
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} queued {}", this, entry);

        flusher.iterate();
    }

    public void close()
    {
        if (closed.compareAndSet(false, true))
        {
            LOG.debug("{} closing {}", this);
            EOFException eof = new EOFException("Connection has been closed locally");
            flusher.failed(eof);

            // Fail also queued entries.
            List<FrameEntry> entries = new ArrayList<>();
            synchronized (lock)
            {
                entries.addAll(queue);
                queue.clear();
            }
            // Notify outside sync block.
            for (FrameEntry entry : entries)
                notifyCallbackFailure(entry.callback, eof);
        }
    }

    protected void onFailure(Throwable x)
    {
        LOG.warn(x);
    }

    protected void notifyCallbackSuccess(WriteCallback callback)
    {
        try
        {
            if (callback != null)
                callback.writeSuccess();
        }
        catch (Throwable x)
        {
            LOG.debug("Exception while notifying success of callback " + callback, x);
        }
    }

    protected void notifyCallbackFailure(WriteCallback callback, Throwable failure)
    {
        try
        {
            if (callback != null)
                callback.writeFailed(failure);
        }
        catch (Throwable x)
        {
            LOG.debug("Exception while notifying failure of callback " + callback, x);
        }
    }

    @Override
    public String toString()
    {
        ByteBuffer aggregate = flusher.aggregate;
        return String.format("%s[queueSize=%d,aggregateSize=%d,failure=%s]",
                getClass().getSimpleName(),
                queue.size(),
                aggregate == null ? 0 : aggregate.position(),
                failure);
    }

    private class Flusher extends IteratingCallback
    {
        private final List<FrameEntry> entries = new ArrayList<>(maxGather);
        private final List<ByteBuffer> buffers = new ArrayList<>(maxGather * 2 + 1);
        private ByteBuffer aggregate;
        private boolean releaseAggregate;

        @Override
        protected Action process() throws Exception
        {
            int space = aggregate == null ? bufferSize : aggregate.remaining();
            boolean batch = true;
            synchronized (lock)
            {
                while (entries.size() <= maxGather && !queue.isEmpty())
                {
                    FrameEntry entry = queue.remove(0);
                    batch &= entry.flushMode == OutgoingFrames.FlushMode.AUTO;

                    // Force flush if we need to.
                    if (entry.frame == FLUSH_FRAME)
                        batch = false;

                    int payloadLength = BufferUtil.length(entry.frame.getPayload());
                    int approxFrameLength = Generator.MAX_HEADER_LENGTH + payloadLength;

                    // If it is a "big" frame, avoid copying into the aggregate buffer.
                    if (approxFrameLength > (bufferSize >> 2))
                        batch = false;

                    // If the aggregate buffer overflows, do not batch.
                    space -= approxFrameLength;
                    if (space <= 0)
                        batch = false;

                    entries.add(entry);
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("{} processing {} entries: {}", FrameFlusher.this, entries.size(), entries);

            if (entries.isEmpty())
            {
                if (releaseAggregate)
                {
                    bufferPool.release(aggregate);
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} released aggregate buffer {}", FrameFlusher.this, aggregate);
                    aggregate = null;
                }
                return Action.IDLE;
            }

            if (batch)
                batch();
            else
                flush();

            return Action.SCHEDULED;
        }

        @SuppressWarnings("ForLoopReplaceableByForEach")
        private void flush()
        {
            if (!BufferUtil.isEmpty(aggregate))
            {
                BufferUtil.flipToFlush(aggregate, 0);
                buffers.add(aggregate);
                releaseAggregate = true;
                if (LOG.isDebugEnabled())
                    LOG.debug("{} flushing aggregate {}", FrameFlusher.this, aggregate);
            }

            // Do not allocate the iterator here.
            for (int i = 0; i < entries.size(); ++i)
            {
                FrameEntry entry = entries.get(i);
                // Skip "synthetic" frames used for flushing.
                if (entry.frame == FLUSH_FRAME)
                    continue;
                buffers.add(entry.getHeaderBytes());
                ByteBuffer payload = entry.frame.getPayload();
                if (BufferUtil.hasContent(payload))
                    buffers.add(payload);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("{} flushing {} frames: {}", FrameFlusher.this, entries.size(), entries);
            endpoint.write(this, buffers.toArray(new ByteBuffer[buffers.size()]));
            buffers.clear();
        }

        @SuppressWarnings("ForLoopReplaceableByForEach")
        private void batch()
        {
            if (aggregate == null)
            {
                aggregate = bufferPool.acquire(bufferSize, true);
                BufferUtil.flipToFill(aggregate);
                if (LOG.isDebugEnabled())
                    LOG.debug("{} acquired aggregate buffer {}", FrameFlusher.this, aggregate);
                releaseAggregate = false;
            }

            // Do not allocate the iterator here.
            for (int i = 0; i < entries.size(); ++i)
            {
                FrameEntry entry = entries.get(i);
                // TODO: would be better to generate the header bytes directly into the aggregate buffer.
                ByteBuffer header = entry.getHeaderBytes();
                aggregate.put(header);

                ByteBuffer payload = entry.frame.getPayload();
                if (BufferUtil.hasContent(payload))
                    aggregate.put(payload);
            }
            if (LOG.isDebugEnabled())
                LOG.debug("{} aggregated {} frames: {}", FrameFlusher.this, entries.size(), entries);
            succeeded();
        }

        @SuppressWarnings("ForLoopReplaceableByForEach")
        @Override
        public void succeeded()
        {
            // Do not allocate the iterator here.
            for (int i = 0; i < entries.size(); ++i)
            {
                FrameEntry entry = entries.get(i);
                notifyCallbackSuccess(entry.callback);
                entry.release();
            }
            entries.clear();

            // Do not release the aggregate yet, in case there are more frames to process.
            if (releaseAggregate)
                BufferUtil.clearToFill(aggregate);

            super.succeeded();
        }

        @Override
        protected void completed()
        {
            // This IteratingCallback never completes.
        }

        @Override
        public void failed(Throwable x)
        {
            for (FrameEntry entry : entries)
            {
                notifyCallbackFailure(entry.callback, x);
                entry.release();
            }
            entries.clear();
            super.failed(x);
            failure = x;
            onFailure(x);
        }
    }

    private class FrameEntry
    {
        private final Frame frame;
        private final WriteCallback callback;
        private final OutgoingFrames.FlushMode flushMode;
        private ByteBuffer headerBuffer;

        private FrameEntry(Frame frame, WriteCallback callback, OutgoingFrames.FlushMode flushMode)
        {
            this.frame = Objects.requireNonNull(frame);
            this.callback = callback;
            this.flushMode = flushMode;
        }

        private ByteBuffer getHeaderBytes()
        {
            return headerBuffer = generator.generateHeaderBytes(frame);
        }

        private void release()
        {
            if (headerBuffer != null)
            {
                generator.getBufferPool().release(headerBuffer);
                headerBuffer = null;
            }
        }

        public String toString()
        {
            return String.format("%s[%s,%s,%s,%s]", getClass().getSimpleName(), frame, callback, flushMode, failure);
        }
    }
}
