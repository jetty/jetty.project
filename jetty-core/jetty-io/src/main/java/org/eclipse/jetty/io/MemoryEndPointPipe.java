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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Memory-based implementation of {@link EndPoint.Pipe}.</p>
 */
public class MemoryEndPointPipe implements EndPoint.Pipe
{
    private final AutoLock lock = new AutoLock();
    private final WritableBufferPool byteBufferPool;
    private final LocalEndPoint localEndPoint;
    private final RemoteEndPoint remoteEndPoint;
    private final Consumer<Invocable.Task> taskConsumer;

    public MemoryEndPointPipe(Scheduler scheduler, Consumer<Invocable.Task> consumer, SocketAddress socketAddress)
    {
        this(scheduler, null, consumer, socketAddress);
    }

    public MemoryEndPointPipe(Scheduler scheduler, ByteBufferPool bufferPool, Consumer<Invocable.Task> consumer, SocketAddress socketAddress)
    {
        byteBufferPool = WritableBufferPool.wrap(Objects.requireNonNullElse(bufferPool, ByteBufferPool.NON_POOLING));
        localEndPoint = new LocalEndPoint(scheduler, new MemorySocketAddress());
        remoteEndPoint = new RemoteEndPoint(scheduler, socketAddress);
        localEndPoint.setPeerEndPoint(remoteEndPoint);
        remoteEndPoint.setPeerEndPoint(localEndPoint);
        taskConsumer = consumer;
    }

    @Override
    public EndPoint getLocalEndPoint()
    {
        return localEndPoint;
    }

    @Override
    public EndPoint getRemoteEndPoint()
    {
        return remoteEndPoint;
    }

    public void setLocalEndPointMaxCapacity(int maxCapacity)
    {
        localEndPoint.setMaxCapacity(maxCapacity);
    }

    public void setRemoteEndPointMaxCapacity(int maxCapacity)
    {
        remoteEndPoint.setMaxCapacity(maxCapacity);
    }

    /**
     * <p>Memory-based {@link EndPoint}.</p>
     * <p>Data written via {@link #flush(RetainableByteBuffer)} is stored in RetainableByteBuffers in a queue,
     * and read via {@link #fill(RetainableByteBuffer.Mutable)} from the peer's queue.
     * EOF is tracked using a sentinel in the queue to ensure proper ordering of data and EOF signals.</p>
     */
    private class MemoryEndPoint extends AbstractEndPoint
    {
        private static final Logger LOG = LoggerFactory.getLogger(MemoryEndPoint.class);
        private static final RetainableByteBuffer EOF = RetainableByteBuffer.wrap(BufferUtil.EMPTY_BUFFER, Retainable.NON_RETAINABLE);

        private final Deque<RetainableByteBuffer> buffers = new ArrayDeque<>();
        private final SocketAddress localAddress;
        private MemoryEndPoint peerEndPoint;
        private Invocable.Task fillableTask;
        private Invocable.Task completeWriteTask;
        private long maxCapacity;
        private long capacity;

        private MemoryEndPoint(Scheduler scheduler, SocketAddress localAddress)
        {
            super(scheduler);
            this.localAddress = localAddress;
        }

        void setPeerEndPoint(MemoryEndPoint peerEndPoint)
        {
            this.peerEndPoint = peerEndPoint;
            this.fillableTask = new FillableTask(peerEndPoint.getFillInterest());
            this.completeWriteTask = new CompleteWriteTask(peerEndPoint.getWriteFlusher());
        }

        public long getMaxCapacity()
        {
            return maxCapacity;
        }

        public void setMaxCapacity(long maxCapacity)
        {
            this.maxCapacity = maxCapacity;
        }

        @Override
        public Object getTransport()
        {
            return null;
        }

        @Override
        public SocketAddress getLocalSocketAddress()
        {
            return localAddress;
        }

        @Override
        public SocketAddress getRemoteSocketAddress()
        {
            return peerEndPoint.getLocalSocketAddress();
        }

        @Override
        protected void onIncompleteFlush()
        {
        }

        @Override
        protected void needsFillInterest()
        {
        }

        @Override
        public int fill(RetainableByteBuffer.Mutable buffer) throws IOException
        {
            if (!isOpen())
                throw new IOException("closed");
            if (isInputShutdown())
                return -1;

            int filled = peerEndPoint.fillInto(buffer);

            if (LOG.isDebugEnabled())
                LOG.debug("filled {} from {}", filled, this);

            if (filled > 0)
            {
                notIdle();
                onFilled();
            }
            else if (filled < 0)
            {
                shutdownInput();
            }

            return filled;
        }

        private int fillInto(RetainableByteBuffer.Mutable buffer)
        {
            int filled = 0;
            try (AutoLock ignored = lock.lock())
            {
                while (true)
                {
                    RetainableByteBuffer data = buffers.peek();
                    if (data == null)
                        return filled;
                    if (data == EOF)
                        return filled > 0 ? filled : -1;

                    int space = (int)buffer.space();
                    if (space == 0)
                        return filled;

                    long available = data.remaining();
                    long toCopy = Math.min(space, available);

                    if (toCopy == available)
                    {
                        buffer.put(data);
                        data.release();
                        buffers.poll();
                    }
                    else
                    {
                        buffer.append(data);
                    }

                    capacity -= toCopy;
                    filled += toCopy;
                }
            }
        }

        private void onFilled()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("filled, notifying completeWrite {}", this);
            taskConsumer.accept(completeWriteTask);
        }

        @Override
        public void fillInterested(Callback callback)
        {
            try (AutoLock ignored = lock.lock())
            {
                // Checking for data and setting the callback must be atomic,
                // otherwise the notification issued by a write() may be lost.
                if (peerEndPoint.buffers.isEmpty())
                {
                    super.fillInterested(callback);
                    return;
                }
            }
            if (LOG.isDebugEnabled())
                LOG.debug("fill interested, data available {}", this);
            callback.succeeded();
        }

        @Override
        public boolean tryFillInterested(Callback callback)
        {
            try (AutoLock ignored = lock.lock())
            {
                // Checking for data and setting the callback must be atomic,
                // otherwise the notification issued by a write() may be lost.
                if (peerEndPoint.buffers.isEmpty())
                    return super.tryFillInterested(callback);
            }
            if (LOG.isDebugEnabled())
                LOG.debug("try fill interested, data available {}", this);
            callback.succeeded();
            return false;
        }

        @Override
        public boolean flush(RetainableByteBuffer buffer) throws IOException
        {
            if (!isOpen())
                throw new IOException("closed");
            if (isOutputShutdown())
                throw new IOException("shutdown");

            long flushed = 0;
            boolean result = true;
            try (AutoLock ignored = lock.lock())
            {
                // The peer EndPoint may have been closed, and its close() terminated
                // this EndPoint's write queue with EOF; no data can be appended after
                // EOF, as it would never be read, nor released, by the peer EndPoint.
                if (buffers.peekLast() == EOF)
                    throw new IOException("closed");

                long remaining = buffer.remaining();
                if (remaining > 0)
                {
                    // The buffer must be copied, otherwise a write() would complete
                    // and return it to the buffer pool where its backing store would
                    // be overwritten before it is read by the peer EndPoint.
                    RetainableByteBuffer toRead = lockedCopy(buffer);
                    if (toRead == null)
                    {
                        result = false;
                    }
                    else
                    {
                        lockedOffer(buffers, toRead);
                        long length = toRead.remaining();
                        capacity += length;
                        flushed += length;
                        if (length < remaining)
                            result = false;
                    }
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("flushed {} to {}", flushed, this);

            if (flushed > 0)
            {
                notIdle();
                onFlushed();
            }

            return result;
        }

        private RetainableByteBuffer lockedCopy(RetainableByteBuffer buffer)
        {
            long remaining = buffer.remaining();
            int length = (int)Math.min(IO.DEFAULT_BUFFER_SIZE, remaining);
            long maxCapacity = getMaxCapacity();
            if (maxCapacity > 0)
            {
                long space = maxCapacity - capacity;
                if (space == 0)
                    return null;
                length = (int)Math.min(length, space);
            }

            RetainableByteBuffer.Mutable copy = byteBufferPool.acquire(length, false);
            if (length < remaining)
            {
                // Partial copy of the buffer.
                RetainableByteBuffer slice = buffer.sliceAndConsume(length);
                copy.put(slice);
                slice.release();
            }
            else
            {
                copy.put(buffer);
            }
            return copy;
        }

        private void lockedOffer(Deque<RetainableByteBuffer> buffers, RetainableByteBuffer buffer)
        {
            assert lock.isHeldByCurrentThread();
            if (LOG.isDebugEnabled())
                LOG.debug("flush {} to {}", buffer, this);
            buffers.offer(buffer);
        }

        @Override
        protected void doShutdownOutput()
        {
            super.doShutdownOutput();
            try (AutoLock ignored = lock.lock())
            {
                lockedOffer(buffers, EOF);
            }
            onFlushed();
        }

        @Override
        protected void doClose()
        {
            super.doClose();

            List<RetainableByteBuffer> toRelease;
            try (AutoLock ignored = lock.lock())
            {
                // Close the write side.
                RetainableByteBuffer last = buffers.peekLast();
                if (last != EOF)
                    lockedOffer(buffers, EOF);

                // Drop the read side.
                Deque<RetainableByteBuffer> readBuffers = peerEndPoint.buffers;
                toRelease = readBuffers.isEmpty() ? List.of() : new ArrayList<>(readBuffers);
                readBuffers.clear();
                lockedOffer(readBuffers, EOF);
            }
            toRelease.forEach(RetainableByteBuffer::release);
            onFlushed();
        }

        private void onFlushed()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("flushed, notifying fillable {}", this);
            taskConsumer.accept(fillableTask);
        }

        @Override
        public void onClose(Throwable failure)
        {
            super.onClose(failure);
            Connection connection = getConnection();
            if (connection != null)
                connection.onClose(failure);
        }
    }

    private class LocalEndPoint extends MemoryEndPoint
    {
        private LocalEndPoint(Scheduler scheduler, SocketAddress socketAddress)
        {
            super(scheduler, socketAddress);
        }
    }

    private class RemoteEndPoint extends MemoryEndPoint
    {
        private RemoteEndPoint(Scheduler scheduler, SocketAddress socketAddress)
        {
            super(scheduler, socketAddress);
        }
    }

    private record FillableTask(FillInterest fillInterest) implements Invocable.Task
    {
        @Override
        public void run()
        {
            fillInterest.fillable();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return fillInterest.getCallbackInvocationType();
        }
    }

    private record CompleteWriteTask(WriteFlusher writeFlusher) implements Invocable.Task
    {
        @Override
        public void run()
        {
            writeFlusher.completeWrite();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return writeFlusher.getCallbackInvocationType();
        }
    }

    private static class MemorySocketAddress extends SocketAddress
    {
        private static final AtomicInteger IDS = new AtomicInteger();

        private final int id = IDS.incrementAndGet();
        private final String address = "memory_%08x".formatted(id);

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj instanceof MemorySocketAddress that)
                return id == that.id;
            return false;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(id);
        }

        @Override
        public String toString()
        {
            return address;
        }
    }
}
