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
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
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
    private final ByteBufferPool byteBufferPool;
    private final LocalEndPoint localEndPoint;
    private final RemoteEndPoint remoteEndPoint;
    private final Consumer<Invocable.Task> taskConsumer;

    public MemoryEndPointPipe(Scheduler scheduler, Consumer<Invocable.Task> consumer, SocketAddress socketAddress)
    {
        this(scheduler, null, consumer, socketAddress);
    }

    public MemoryEndPointPipe(Scheduler scheduler, ByteBufferPool bufferPool, Consumer<Invocable.Task> consumer, SocketAddress socketAddress)
    {
        byteBufferPool = Objects.requireNonNullElse(bufferPool, ByteBufferPool.NON_POOLING);
        localEndPoint = new LocalEndPoint(scheduler, socketAddress);
        remoteEndPoint = new RemoteEndPoint(scheduler, new MemorySocketAddress());
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
     * <p>Memory-based {@link EndPoint} that uses {@link RetainableByteBuffer.DynamicCapacity}
     * for efficient buffer management.</p>
     * <p>Data written via {@link #flush(ByteBuffer...)} is stored in RetainableByteBuffers in a queue,
     * and read via {@link #fill(ByteBuffer)} from the peer's queue. EOF is tracked using a sentinel
     * in the queue to ensure proper ordering of data and EOF signals.</p>
     */
    private class MemoryEndPoint extends AbstractEndPoint
    {
        private static final Logger LOG = LoggerFactory.getLogger(MemoryEndPoint.class);
        private static final RetainableByteBuffer EOF = RetainableByteBuffer.wrap(BufferUtil.EMPTY_BUFFER);

        private final AutoLock lock = new AutoLock();
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
        public int fill(ByteBuffer buffer) throws IOException
        {
            if (!isOpen())
                throw new IOException("closed");
            if (isInputShutdown())
                return -1;

            int position = BufferUtil.flipToFill(buffer);
            int filled = peerEndPoint.fillInto(buffer);
            BufferUtil.flipToFlush(buffer, position);

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

        private int fillInto(ByteBuffer buffer)
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

                    int space = buffer.remaining();
                    if (space == 0)
                        return filled;

                    int available = data.remaining();
                    int toCopy = Math.min(space, available);

                    if (toCopy == available)
                    {
                        data.putTo(buffer);
                        data.release();
                        buffers.poll();
                    }
                    else
                    {
                        RetainableByteBuffer slice = data.slice(toCopy);
                        slice.putTo(buffer);
                        slice.release();
                        data.skip(toCopy);
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
        public boolean flush(ByteBuffer... buffers) throws IOException
        {
            if (!isOpen())
                throw new IOException("closed");
            if (isOutputShutdown())
                throw new IOException("shutdown");

            long flushed = 0;
            boolean result = true;
            try (AutoLock ignored = lock.lock())
            {
                for (ByteBuffer buffer : buffers)
                {
                    int remaining = buffer.remaining();
                    if (remaining == 0)
                        continue;

                    // The buffer must be copied, otherwise a write() would complete
                    // and return it to the buffer pool where its backing store would
                    // be overwritten before it is read by the peer EndPoint.
                    RetainableByteBuffer copy = lockedCopy(buffer);
                    if (copy == null)
                    {
                        result = false;
                        break;
                    }
                    this.buffers.offer(copy);
                    int length = copy.remaining();
                    capacity += length;
                    flushed += length;
                    if (length < remaining)
                    {
                        result = false;
                        break;
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

        private RetainableByteBuffer lockedCopy(ByteBuffer buffer)
        {
            int remaining = buffer.remaining();
            int length = remaining;
            long maxCapacity = getMaxCapacity();
            if (maxCapacity > 0)
            {
                long space = maxCapacity - capacity;
                if (space == 0)
                    return null;
                length = (int)Math.min(length, space);
            }

            RetainableByteBuffer.Mutable copy = byteBufferPool.acquire(length, buffer.isDirect());
            if (length < remaining)
            {
                // Partial copy.
                copy.append(buffer.slice(buffer.position(), length));
                buffer.position(buffer.position() + length);
            }
            else
            {
                copy.append(buffer);
            }
            return copy;
        }

        @Override
        protected void doShutdownOutput()
        {
            super.doShutdownOutput();
            try (AutoLock ignored = lock.lock())
            {
                buffers.offer(EOF);
            }
            onFlushed();
        }

        @Override
        protected void doClose()
        {
            super.doClose();
            try (AutoLock ignored = lock.lock())
            {
                RetainableByteBuffer last = buffers.peekLast();
                if (last != EOF)
                    buffers.offer(EOF);
            }
            onFlushed();
        }

        private void onFlushed()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("flushed, notifying fillable {}", this);
            taskConsumer.accept(fillableTask);
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
        private static final AtomicLong ID = new AtomicLong();

        private final long id = ID.incrementAndGet();
        private final String address = "[memory:/%s]".formatted(HexFormat.of().formatHex(ByteBuffer.allocate(8).putLong(id).array()));

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
