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
    private static final Logger LOG = LoggerFactory.getLogger(MemoryEndPointPipe.class);

    private final LocalEndPoint localEndPoint;
    private final RemoteEndPoint remoteEndPoint;
    private final Consumer<Invocable.Task> taskConsumer;

    public MemoryEndPointPipe(Scheduler scheduler, Consumer<Invocable.Task> consumer, SocketAddress socketAddress)
    {
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

    private class MemoryEndPoint extends AbstractEndPoint
    {
        private static final ByteBuffer EOF = ByteBuffer.allocate(0);

        private final AutoLock lock = new AutoLock();
        private final Deque<ByteBuffer> byteBuffers = new ArrayDeque<>();
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

        private int fillInto(ByteBuffer buffer)
        {
            int filled = 0;
            try (AutoLock ignored = lock.lock())
            {
                while (true)
                {
                    ByteBuffer data = byteBuffers.peek();
                    if (data == null)
                        return filled;
                    if (data == EOF)
                        return -1;
                    int length = data.remaining();
                    int copied = BufferUtil.append(buffer, data);
                    capacity -= copied;
                    filled += copied;
                    if (copied < length)
                        return filled;
                    byteBuffers.poll();
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
                if (peerEndPoint.byteBuffers.isEmpty())
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
                if (peerEndPoint.byteBuffers.isEmpty())
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
                    ByteBuffer copy = copy(buffer);
                    if (copy == null)
                    {
                        result = false;
                        break;
                    }
                    byteBuffers.offer(copy);
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

        private ByteBuffer copy(ByteBuffer buffer)
        {
            int length = buffer.remaining();
            long maxCapacity = getMaxCapacity();
            if (maxCapacity > 0)
            {
                long space = maxCapacity - capacity;
                if (space == 0)
                    return null;
                length = (int)Math.min(length, space);
            }
            // TODO: Use RetainableByteBuffer.DynamicCapacity in Jetty 12.1.x.
            ByteBuffer copy = buffer.isDirect() ? ByteBuffer.allocateDirect(length) : ByteBuffer.allocate(length);
            copy.put(0, buffer, buffer.position(), length);
            buffer.position(buffer.position() + length);
            return copy;
        }

        @Override
        protected void doShutdownOutput()
        {
            super.doShutdownOutput();
            try (AutoLock ignored = lock.lock())
            {
                byteBuffers.offer(EOF);
            }
            onFlushed();
        }

        @Override
        protected void doClose()
        {
            super.doClose();
            try (AutoLock ignored = lock.lock())
            {
                ByteBuffer last = byteBuffers.peekLast();
                if (last != EOF)
                    byteBuffers.offer(EOF);
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
