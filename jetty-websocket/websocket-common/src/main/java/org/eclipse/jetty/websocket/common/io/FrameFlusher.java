//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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

/**
 * Interface for working with bytes destined for {@link EndPoint#write(org.eclipse.jetty.util.Callback, ByteBuffer...)}
 */
public class FrameFlusher
{
    private class Flusher extends IteratingCallback
    {
        private final List<FrameEntry> entries;
        private final List<ByteBuffer> buffers;
        private ByteBuffer aggregate;
        private BatchMode batchMode;

        public Flusher(int maxGather)
        {
            entries = new ArrayList<>(maxGather);
            buffers = new ArrayList<>((maxGather * 2) + 1);
        }

        private Action batch()
        {
            if (aggregate == null)
            {
                aggregate = bufferPool.acquire(bufferSize,true);
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("{} acquired aggregate buffer {}",FrameFlusher.this,aggregate);
                }
            }

            // Do not allocate the iterator here.
            for (int i = 0; i < entries.size(); ++i)
            {
                FrameEntry entry = entries.get(i);

                entry.generateHeaderBytes(aggregate);

                ByteBuffer payload = entry.frame.getPayload();
                if (BufferUtil.hasContent(payload))
                {
                    BufferUtil.append(aggregate,payload);
                }
            }
            if (LOG.isDebugEnabled())
            {
                LOG.debug("{} aggregated {} frames: {}",FrameFlusher.this,entries.size(),entries);
            }
            succeeded();
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            // This IteratingCallback never completes.
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            for (FrameEntry entry : entries)
            {
                notifyCallbackFailure(entry.callback,x);
                entry.release();
            }
            entries.clear();
            failure = x;
            onFailure(x);
        }

        private Action flush()
        {
            if (!BufferUtil.isEmpty(aggregate))
            {
                buffers.add(aggregate);
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("{} flushing aggregate {}",FrameFlusher.this,aggregate);
                }
            }

            // Do not allocate the iterator here.
            for (int i = 0; i < entries.size(); ++i)
            {
                FrameEntry entry = entries.get(i);
                // Skip the "synthetic" frame used for flushing.
                if (entry.frame == FLUSH_FRAME)
                {
                    continue;
                }
                buffers.add(entry.generateHeaderBytes());
                ByteBuffer payload = entry.frame.getPayload();
                if (BufferUtil.hasContent(payload))
                {
                    buffers.add(payload);
                }
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug("{} flushing {} frames: {}",FrameFlusher.this,entries.size(),entries);
            }

            if (buffers.isEmpty())
            {
                releaseAggregate();
                // We may have the FLUSH_FRAME to notify.
                succeedEntries();
                return Action.IDLE;
            }

            endpoint.write(this,buffers.toArray(new ByteBuffer[buffers.size()]));
            buffers.clear();
            return Action.SCHEDULED;
        }

        @Override
        protected Action process() throws Exception
        {
            int space = aggregate == null?bufferSize:BufferUtil.space(aggregate);
            BatchMode currentBatchMode = BatchMode.AUTO;
            synchronized (lock)
            {
                while ((entries.size() <= maxGather) && !queue.isEmpty())
                {
                    FrameEntry entry = queue.poll();
                    currentBatchMode = BatchMode.max(currentBatchMode,entry.batchMode);

                    // Force flush if we need to.
                    if (entry.frame == FLUSH_FRAME)
                    {
                        currentBatchMode = BatchMode.OFF;
                    }

                    int payloadLength = BufferUtil.length(entry.frame.getPayload());
                    int approxFrameLength = Generator.MAX_HEADER_LENGTH + payloadLength;

                    // If it is a "big" frame, avoid copying into the aggregate buffer.
                    if (approxFrameLength > (bufferSize >> 2))
                    {
                        currentBatchMode = BatchMode.OFF;
                    }

                    // If the aggregate buffer overflows, do not batch.
                    space -= approxFrameLength;
                    if (space <= 0)
                    {
                        currentBatchMode = BatchMode.OFF;
                    }

                    entries.add(entry);
                }
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug("{} processing {} entries: {}",FrameFlusher.this,entries.size(),entries);
            }

            if (entries.isEmpty())
            {
                if (batchMode != BatchMode.AUTO)
                {
                    // Nothing more to do, release the aggregate buffer if we need to.
                    // Releasing it here rather than in succeeded() allows for its reuse.
                    releaseAggregate();
                    return Action.IDLE;
                }

                LOG.debug("{} auto flushing",FrameFlusher.this);
                return flush();
            }

            batchMode = currentBatchMode;

            return currentBatchMode == BatchMode.OFF?flush():batch();
        }

        private void releaseAggregate()
        {
            if ((aggregate != null) && BufferUtil.isEmpty(aggregate))
            {
                bufferPool.release(aggregate);
                aggregate = null;
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
            // Do not allocate the iterator here.
            for (int i = 0; i < entries.size(); ++i)
            {
                FrameEntry entry = entries.get(i);
                notifyCallbackSuccess(entry.callback);
                entry.release();
            }
            entries.clear();
        }
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
            generator.generateHeaderBytes(frame,buffer);
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
            return String.format("%s[%s,%s,%s,%s]",getClass().getSimpleName(),frame,callback,batchMode,failure);
        }
    }

    public static final BinaryFrame FLUSH_FRAME = new BinaryFrame();
    private static final Logger LOG = Log.getLogger(FrameFlusher.class);
    private final ByteBufferPool bufferPool;
    private final EndPoint endpoint;
    private final int bufferSize;
    private final Generator generator;
    private final int maxGather;
    private final Object lock = new Object();
    private final Deque<FrameEntry> queue = new ArrayDeque<>();
    private final Flusher flusher;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Throwable failure;

    public FrameFlusher(ByteBufferPool bufferPool, Generator generator, EndPoint endpoint, int bufferSize, int maxGather)
    {
        this.bufferPool = bufferPool;
        this.endpoint = endpoint;
        this.bufferSize = bufferSize;
        this.generator = Objects.requireNonNull(generator);
        this.maxGather = maxGather;
        this.flusher = new Flusher(maxGather);
    }

    public void close()
    {
        if (closed.compareAndSet(false,true))
        {
            LOG.debug("{} closing {}",this);
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
            {
                notifyCallbackFailure(entry.callback,eof);
            }
        }
    }

    public void enqueue(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        if (closed.get())
        {
            notifyCallbackFailure(callback,new EOFException("Connection has been closed locally"));
            return;
        }
        if (flusher.isFailed())
        {
            notifyCallbackFailure(callback,failure);
            return;
        }

        FrameEntry entry = new FrameEntry(frame,callback,batchMode);

        synchronized (lock)
        {
            switch (frame.getOpCode())
            {
                case OpCode.PING:
                {
                    // Prepend PINGs so they are processed first.
                    queue.offerFirst(entry);
                    break;
                }
                case OpCode.CLOSE:
                {
                    // There may be a chance that other frames are
                    // added after this close frame, but we will
                    // fail them later to keep it simple here.
                    closed.set(true);
                    queue.offer(entry);
                    break;
                }
                default:
                {
                    queue.offer(entry);
                    break;
                }
            }
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("{} queued {}",this,entry);
        }

        flusher.iterate();
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
                LOG.debug("Exception while notifying failure of callback " + callback,x);
        }
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
                LOG.debug("Exception while notifying success of callback " + callback,x);
        }
    }

    protected void onFailure(Throwable x)
    {
        LOG.warn(x);
    }

    @Override
    public String toString()
    {
        ByteBuffer aggregate = flusher.aggregate;
        return String.format("%s[queueSize=%d,aggregateSize=%d,failure=%s]",getClass().getSimpleName(),queue.size(),aggregate == null?0:aggregate.position(),
                failure);
    }
}
