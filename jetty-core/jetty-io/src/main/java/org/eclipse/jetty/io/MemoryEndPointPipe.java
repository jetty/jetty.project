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
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * <p>Memory-based {@link EndPoint} that uses {@link RetainableByteBuffer.DynamicCapacity}
     * for efficient buffer management.</p>
     */
    private class MemoryEndPoint extends AbstractEndPoint
    {
        private static final Logger LOG = LoggerFactory.getLogger(MemoryEndPoint.class);
        // Sequence counter for tracing events across threads
        private static final AtomicInteger SEQ = new AtomicInteger();

        private final AutoLock lock = new AutoLock();
        private final RetainableByteBuffer.DynamicCapacity buffer = new RetainableByteBuffer.DynamicCapacity();
        private final SocketAddress localAddress;
        private final String name;
        private MemoryEndPoint peerEndPoint;
        private Invocable.Task fillableTask;
        private Invocable.Task completeWriteTask;
        private long maxCapacity;
        private boolean eof;

        private MemoryEndPoint(Scheduler scheduler, SocketAddress localAddress, String name)
        {
            super(scheduler);
            this.localAddress = localAddress;
            this.name = name;
        }

        private void trace(String format, Object... args)
        {
            if (LOG.isDebugEnabled())
            {
                String msg = "[%03d] [%s] %s: ".formatted(SEQ.incrementAndGet(), Thread.currentThread().getName(), name) + format.formatted(args);
                LOG.debug(msg);
            }
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
            trace("fill() called, destRemaining=%d, destPos=%d, destLimit=%d",
                buffer.remaining(), buffer.position(), buffer.limit());

            if (!isOpen())
                throw new IOException("closed");
            if (isInputShutdown())
            {
                trace("fill() -> -1 (input shutdown)");
                return -1;
            }

            int filled = peerEndPoint.fillInto(buffer);

            trace("fill() -> %d, destRemaining=%d", filled, buffer.remaining());

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

        private int fillInto(ByteBuffer dest)
        {
            try (AutoLock l = lock.lock())
            {
                trace("fillInto() called, bufferSize=%d, eof=%b, destRemaining=%d",
                    buffer.size(), eof, dest.remaining());

                // Check for data or EOF
                if (buffer.isEmpty())
                {
                    int result = eof ? -1 : 0;
                    trace("fillInto() -> %d (buffer empty, eof=%b)", result, eof);
                    return result;
                }

                // Use flipToFill/flipToFlush to properly handle the destination buffer state.
                // This allows the buffer to be reused across multiple fill calls.
                int pos = BufferUtil.flipToFill(dest);
                trace("fillInto() after flipToFill: destPos=%d, destLimit=%d, destRemaining=%d",
                    dest.position(), dest.limit(), dest.remaining());
                try
                {
                    int filled = 0;
                    while (!buffer.isEmpty())
                    {
                        int space = dest.remaining();
                        if (space == 0)
                        {
                            trace("fillInto() breaking - no space in dest");
                            break;
                        }

                        int available = (int)Math.min(space, buffer.size());
                        byte[] temp = new byte[available];
                        int read = buffer.get(temp, 0, available);
                        dest.put(temp, 0, read);
                        filled += read;
                        trace("fillInto() copied %d bytes, filled=%d, bufferSize=%d",
                            read, filled, buffer.size());
                    }

                    // If we filled some data and buffer is now empty with EOF pending,
                    // return what we got; next call will return -1
                    if (buffer.isEmpty() && eof)
                    {
                        int result = filled > 0 ? filled : -1;
                        trace("fillInto() -> %d (buffer now empty, eof=%b)", result, eof);
                        return result;
                    }

                    trace("fillInto() -> %d, bufferSize=%d, eof=%b", filled, buffer.size(), eof);
                    return filled;
                }
                finally
                {
                    BufferUtil.flipToFlush(dest, pos);
                }
            }
        }

        private void onFilled()
        {
            trace("onFilled() -> queuing completeWriteTask for peer's WriteFlusher");
            taskConsumer.accept(completeWriteTask);
        }

        @Override
        public void fillInterested(Callback callback)
        {
            // Must hold peer's lock to safely check peer's buffer state.
            try (AutoLock peerLock = peerEndPoint.lock.lock())
            {
                boolean peerEmpty = peerEndPoint.buffer.isEmpty();
                boolean peerEof = peerEndPoint.eof;
                trace("fillInterested() checking peer: peerBufferEmpty=%b, peerEof=%b",
                    peerEmpty, peerEof);

                // Checking for data and setting the callback must be atomic,
                // otherwise the notification issued by a write() may be lost.
                if (peerEmpty && !peerEof)
                {
                    trace("fillInterested() -> registering callback (no data, no eof)");
                    super.fillInterested(callback);
                    return;
                }
            }
            trace("fillInterested() -> callback.succeeded() immediately (data or eof available)");
            callback.succeeded();
        }

        @Override
        public boolean tryFillInterested(Callback callback)
        {
            // Must hold peer's lock to safely check peer's buffer state.
            try (AutoLock peerLock = peerEndPoint.lock.lock())
            {
                boolean peerEmpty = peerEndPoint.buffer.isEmpty();
                boolean peerEof = peerEndPoint.eof;
                trace("tryFillInterested() checking peer: peerBufferEmpty=%b, peerEof=%b",
                    peerEmpty, peerEof);

                // Checking for data and setting the callback must be atomic,
                // otherwise the notification issued by a write() may be lost.
                if (peerEmpty && !peerEof)
                {
                    boolean result = super.tryFillInterested(callback);
                    trace("tryFillInterested() -> %b (registered callback)", result);
                    return result;
                }
            }
            trace("tryFillInterested() -> false (data or eof available, callback.succeeded())");
            callback.succeeded();
            return false;
        }

        @Override
        public boolean flush(ByteBuffer... buffers) throws IOException
        {
            int totalRemaining = 0;
            for (ByteBuffer b : buffers)
                totalRemaining += b.remaining();
            trace("flush() called with %d buffers, totalRemaining=%d, currentBufferSize=%d, maxCapacity=%d",
                buffers.length, totalRemaining, buffer.size(), maxCapacity);

            if (!isOpen())
                throw new IOException("closed");
            if (isOutputShutdown())
                throw new IOException("shutdown");

            long flushed = 0;
            boolean result = true;
            try (AutoLock ignored = lock.lock())
            {
                for (ByteBuffer buf : buffers)
                {
                    int remaining = buf.remaining();
                    if (remaining == 0)
                        continue;

                    // The buffer content is copied into the DynamicCapacity buffer,
                    // otherwise a write() would complete and return it to the buffer
                    // pool where its backing store would be overwritten before it is
                    // read by the peer EndPoint.
                    int before = buf.remaining();
                    if (!lockedAppend(buf))
                    {
                        trace("flush() lockedAppend returned false (at capacity)");
                        result = false;
                        break;
                    }
                    int length = before - buf.remaining();
                    flushed += length;
                    trace("flush() appended %d bytes, flushed=%d, bufferSize=%d",
                        length, flushed, buffer.size());
                    if (length < remaining)
                    {
                        trace("flush() partial write: length=%d < remaining=%d", length, remaining);
                        result = false;
                        break;
                    }
                }
            }

            trace("flush() -> result=%b, flushed=%d, bufferSize=%d", result, flushed, buffer.size());

            if (flushed > 0)
            {
                notIdle();
                onFlushed();
            }

            return result;
        }

        /**
         * Appends data from src to the internal buffer, respecting maxCapacity.
         * When maxCapacity limits how much can be appended, only a portion of src
         * is copied: the limit is temporarily reduced to expose only the allowed
         * bytes, then restored so the caller sees the remaining unconsumed data.
         *
         * @param src the source buffer to append from
         * @return true if any bytes were appended, false if buffer is at capacity
         */
        private boolean lockedAppend(ByteBuffer src)
        {
            int length = src.remaining();
            long maxCap = getMaxCapacity();
            trace("lockedAppend() srcRemaining=%d, maxCap=%d, bufferSize=%d",
                length, maxCap, buffer.size());
            if (maxCap > 0)
            {
                long space = maxCap - buffer.size();
                trace("lockedAppend() space=%d", space);
                if (space == 0)
                {
                    trace("lockedAppend() -> false (no space)");
                    return false;
                }
                length = (int)Math.min(length, space);
            }
            if (length < src.remaining())
            {
                // Partial append: temporarily reduce limit to copy only 'length' bytes,
                // then restore the original limit so caller sees remaining data.
                int limit = src.limit();
                src.limit(src.position() + length);
                buffer.append(src);
                src.limit(limit);
                trace("lockedAppend() partial append: %d bytes, srcRemaining=%d", length, src.remaining());
            }
            else
            {
                buffer.append(src);
                trace("lockedAppend() full append: %d bytes", length);
            }
            return true;
        }

        @Override
        protected void doShutdownOutput()
        {
            trace("doShutdownOutput() called, bufferSize=%d", buffer.size());
            super.doShutdownOutput();
            try (AutoLock ignored = lock.lock())
            {
                eof = true;
                trace("doShutdownOutput() set eof=true, bufferSize=%d", buffer.size());
            }
            onFlushed();
        }

        @Override
        protected void doClose()
        {
            trace("doClose() called, bufferSize=%d, eof=%b", buffer.size(), eof);
            super.doClose();
            try (AutoLock ignored = lock.lock())
            {
                // Set EOF but don't clear the buffer - data should remain
                // readable until EOF is encountered.
                eof = true;
                trace("doClose() set eof=true, bufferSize=%d", buffer.size());
            }
            onFlushed();
        }

        private void onFlushed()
        {
            trace("onFlushed() -> queuing fillableTask for peer's FillInterest");
            taskConsumer.accept(fillableTask);
        }
    }

    private class LocalEndPoint extends MemoryEndPoint
    {
        private LocalEndPoint(Scheduler scheduler, SocketAddress socketAddress)
        {
            super(scheduler, socketAddress, "LOCAL");
        }
    }

    private class RemoteEndPoint extends MemoryEndPoint
    {
        private RemoteEndPoint(Scheduler scheduler, SocketAddress socketAddress)
        {
            super(scheduler, socketAddress, "REMOTE");
        }
    }

    private record FillableTask(FillInterest fillInterest) implements Invocable.Task
    {
        private static final Logger LOG = LoggerFactory.getLogger(FillableTask.class);
        private static final AtomicInteger SEQ = new AtomicInteger();

        @Override
        public void run()
        {
            int seq = SEQ.incrementAndGet();
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] [{}] FillableTask.run() -> fillInterest.fillable()",
                    seq, Thread.currentThread().getName());
            fillInterest.fillable();
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] [{}] FillableTask.run() completed",
                    seq, Thread.currentThread().getName());
        }

        @Override
        public InvocationType getInvocationType()
        {
            return fillInterest.getCallbackInvocationType();
        }
    }

    private record CompleteWriteTask(WriteFlusher writeFlusher) implements Invocable.Task
    {
        private static final Logger LOG = LoggerFactory.getLogger(CompleteWriteTask.class);
        private static final AtomicInteger SEQ = new AtomicInteger();

        @Override
        public void run()
        {
            int seq = SEQ.incrementAndGet();
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] [{}] CompleteWriteTask.run() -> writeFlusher.completeWrite(), isPending={}",
                    seq, Thread.currentThread().getName(), writeFlusher.isPending());
            writeFlusher.completeWrite();
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] [{}] CompleteWriteTask.run() completed, isPending={}",
                    seq, Thread.currentThread().getName(), writeFlusher.isPending());
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
