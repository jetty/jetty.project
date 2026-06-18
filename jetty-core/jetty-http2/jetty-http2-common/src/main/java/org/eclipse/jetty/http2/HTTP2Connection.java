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

package org.eclipse.jetty.http2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Connection extends AbstractConnection implements Parser.Listener, Connection.UpgradeTo
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP2Connection.class);

    private final AutoLock lock = new AutoLock();
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final HTTP2Producer producer = new HTTP2Producer();
    private final AtomicLong bytesIn = new AtomicLong();
    private final WritableBufferPool bufferPool;
    private final HTTP2Session session;
    private final int bufferSize;
    private final int minBufferSpace;
    private final ExecutionStrategy strategy;
    private boolean useInputDirectByteBuffers;
    private boolean useOutputDirectByteBuffers;

    protected HTTP2Connection(WritableBufferPool bufferPool, Executor executor, EndPoint endPoint, HTTP2Session session, int bufferSize)
    {
        this(bufferPool, executor, endPoint, session, bufferSize, -1);
    }

    protected HTTP2Connection(WritableBufferPool bufferPool, Executor executor, EndPoint endPoint, HTTP2Session session, int bufferSize, int minBufferSpace)
    {
        super(endPoint, executor);
        this.bufferPool = bufferPool;
        this.session = session;
        this.bufferSize = bufferSize;
        this.minBufferSpace = minBufferSpace < 0 ? Math.min(1500, bufferSize) : minBufferSpace;
        this.strategy = new AdaptiveExecutionStrategy(producer, executor);
        LifeCycle.start(strategy);
    }

    @Override
    public long getMessagesIn()
    {
        HTTP2Session session = getSession();
        return session.getStreamsOpened();
    }

    @Override
    public long getMessagesOut()
    {
        HTTP2Session session = getSession();
        return session.getStreamsClosed();
    }

    @Override
    public long getBytesIn()
    {
        return bytesIn.get();
    }

    @Override
    public long getBytesOut()
    {
        return session.getBytesWritten();
    }

    public HTTP2Session getSession()
    {
        return session;
    }

    @Override
    public void onUpgradeTo(ByteBuffer buffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 onUpgradeTo {} {}", this, BufferUtil.toDetailString(buffer));
        producer.setInputBuffer(ReadableBuffer.wrap(buffer));
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    public boolean isUseOutputDirectByteBuffers()
    {
        return useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    @Override
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Open {} ", this);
        super.onOpen();
    }

    @Override
    public void onClose(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Close {} ", this);
        super.onClose(cause);
        LifeCycle.stop(strategy);
    }

    @Override
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 onFillable {} ", this);
        produce();
    }

    private int fill(EndPoint endPoint, WritableBuffer buffer, boolean compact)
    {
        long padding = 0L;
        try
        {
            if (endPoint.isInputShutdown())
                return -1;

            if (!compact)
            {
                // Add padding content to avoid compaction
                padding = buffer.position();
                buffer.position(0);
            }
            return endPoint.fill(buffer);
        }
        catch (IOException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not read from {}", endPoint, x);
            return -1;
        }
        finally
        {
            if (!compact && padding > 0)
                buffer.position(padding);
        }
    }

    @Override
    public boolean onIdleExpired(TimeoutException timeoutException)
    {
        boolean idle = isFillInterested();
        if (idle)
        {
            boolean close = session.onIdleTimeout();
            if (close)
                session.close(ErrorCode.NO_ERROR.code, "idle_timeout", Callback.NOOP);
        }
        return false;
    }

    /**
     * @param task  The task to offer to the connection.
     * @param dispatch {@code true} to dispatch the task, {@code false} to produce in the calling thread.
     *                 Callers from application threads should use {@code true}, otherwise they may be arbitrarily
     *                 delayed. Callers from I/O threads should use {@code false} to avoid thread hops.
     */
    public void offerTask(Runnable task, boolean dispatch)
    {
        offerTask(task);
        if (dispatch)
            dispatch();
        else
            produce();
    }

    private void offerTask(Runnable task)
    {
        try (AutoLock ignored = lock.lock())
        {
            tasks.offer(task);
        }
    }

    protected void produce()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 produce {} ", this);
        strategy.produce();
    }

    protected void dispatch()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 dispatch {} ", this);
        strategy.dispatch();
    }

    @Override
    public void close()
    {
        // We don't call super from here, otherwise we close the
        // endPoint and we're not able to read or write anymore.
        session.close(ErrorCode.NO_ERROR.code, "close", Callback.NOOP);
    }

    private Runnable pollTask()
    {
        try (AutoLock ignored = lock.lock())
        {
            return tasks.poll();
        }
    }

    @Override
    public void onHeaders(HeadersFrame frame)
    {
        session.onHeaders(frame);
    }

    @Override
    public void onData(DataFrame frame)
    {
        session.onData(frame);
    }

    @Override
    public void onPriority(PriorityFrame frame)
    {
        session.onPriority(frame);
    }

    @Override
    public void onReset(ResetFrame frame)
    {
        session.onReset(frame);
    }

    @Override
    public void onSettings(SettingsFrame frame)
    {
        session.onSettings(frame);
    }

    @Override
    public void onPushPromise(PushPromiseFrame frame)
    {
        session.onPushPromise(frame);
    }

    @Override
    public void onPing(PingFrame frame)
    {
        session.onPing(frame);
    }

    @Override
    public void onGoAway(GoAwayFrame frame)
    {
        session.onGoAway(frame);
    }

    @Override
    public void onWindowUpdate(WindowUpdateFrame frame)
    {
        session.onWindowUpdate(frame);
    }

    @Override
    public void onStreamFailure(int streamId, int error, String reason)
    {
        session.onStreamFailure(streamId, error, reason);
    }

    @Override
    public void onConnectionFailure(int error, String reason)
    {
        producer.failed = true;
        session.onConnectionFailure(error, reason);
    }

    @Override
    public String toConnectionString()
    {
        return "%s@%x[%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), strategy);
    }

    protected class HTTP2Producer implements ExecutionStrategy.Producer
    {
        private final Callback fillableCallback = new FillableCallback();
        private final AutoLock lock = new AutoLock();
        private boolean shutdown;
        private boolean failed;
        private ReadableBuffer inputBuffer;

        private void setInputBuffer(ReadableBuffer buffer)
        {
            try (AutoLock ignore = lock.lock())
            {
                WritableBuffer networkBuffer = lockedAcquireBuffer();
                BufferUtil.put(buffer, networkBuffer);
                if (buffer.remaining() > 0L)
                {
                    networkBuffer.release();
                    throw new IllegalStateException("overflow");
                }
                inputBuffer = networkBuffer.toReadable();
            }
        }

        @Override
        public Runnable produce()
        {
            Runnable task = pollTask();
            if (LOG.isDebugEnabled())
                LOG.debug("Dequeued task {}", task);
            if (task != null)
                return task;

            if (isFillInterested() || shutdown || failed)
                return null;

            boolean interested = false;
            ReadableBuffer networkBuffer;
            try (AutoLock ignore = lock.lock())
            {
                if (inputBuffer != null)
                {
                    networkBuffer = inputBuffer;
                    inputBuffer = null;
                }
                else
                {
                    networkBuffer = lockedAcquireBuffer().toReadable();
                }
            }
            try
            {
                boolean parse = networkBuffer.remaining() > 0L;

                while (true)
                {
                    boolean compact = true;
                    if (parse)
                    {
                        while (networkBuffer.remaining() > 0L)
                        {
                            session.getParser().parse(networkBuffer);
                            if (failed)
                                return null;
                        }

                        task = pollTask();
                        if (LOG.isDebugEnabled())
                            LOG.debug("Dequeued new task {}", task);
                        if (task != null)
                            return task;
                    }

                    // If the application has retained the content chunks then we must not overwrite content.
                    if (networkBuffer.isRetained())
                    {
                        // If there is sufficient space available, we can top up the buffer rather than allocate a new one
                        if (minBufferSpace > 0 && networkBuffer.remaining() >= minBufferSpace)
                        {
                            // do not compact the buffer
                            compact = false;
                        }
                        else
                        {
                            // otherwise reacquire the buffer and fill into the new buffer.
                            if (LOG.isDebugEnabled())
                                LOG.debug("Released retained {}", networkBuffer);
                            networkBuffer.release();
                            try (AutoLock ignore = lock.lock())
                            {
                                networkBuffer = lockedAcquireBuffer().toReadable();
                            }
                        }
                    }

                    WritableBuffer wb = networkBuffer.toWritable();
                    int filled = fill(getEndPoint(), wb, compact);
                    wb.toReadable();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Filled {} bytes compacted {} {} in {}", filled, compact, networkBuffer, HTTP2Connection.this);

                    if (filled > 0)
                    {
                        bytesIn.addAndGet(filled);
                        parse = true;
                    }
                    else if (filled == 0)
                    {
                        interested = true;
                        return null;
                    }
                    else
                    {
                        shutdown = true;
                        session.onShutdown();
                        // The onShutdown() call above may have produced a task.
                        return pollTask();
                    }
                }
            }
            catch (Throwable x)
            {
                // This should not happen.
                LOG.warn("Unexpected exception while producing {}", this, x);
                session.onConnectionFailure(ErrorCode.INTERNAL_ERROR.code, x.toString());
                return null;
            }
            finally
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Released after process {}", networkBuffer);
                networkBuffer.release();

                if (interested)
                    fillInterested(fillableCallback);
            }
        }

        private WritableBuffer lockedAcquireBuffer()
        {
            assert lock.isHeldByCurrentThread();

            WritableBuffer buffer = bufferPool.acquire(bufferSize, isUseInputDirectByteBuffers());
            if (LOG.isDebugEnabled())
                LOG.debug("Acquired {} in {}", buffer, HTTP2Connection.this);
            return buffer;
        }

        @Override
        public String toString()
        {
            String countState;
            try (AutoLock l = lock.tryLock())
            {
                boolean held = l.isHeldByCurrentThread();
                countState = held ? String.valueOf(tasks.size()) : "undefined";
            }
            return "%s@%x[taskQueue=%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), countState);
        }
    }

    private class FillableCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            onFillable();
        }

        @Override
        public void failed(Throwable x)
        {
            onFillInterestedFailed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.EITHER;
        }
    }
}
