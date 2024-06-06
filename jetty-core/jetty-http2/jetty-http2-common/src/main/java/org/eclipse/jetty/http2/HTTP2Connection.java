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

import org.eclipse.jetty.http2.api.Stream;
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
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.Retainable;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
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
    private final ByteBufferPool bufferPool;
    private final HTTP2Session session;
    private final int bufferSize;
    private final ExecutionStrategy strategy;
    private boolean useInputDirectByteBuffers;
    private boolean useOutputDirectByteBuffers;

    protected HTTP2Connection(ByteBufferPool bufferPool, Executor executor, EndPoint endPoint, HTTP2Session session, int bufferSize)
    {
        super(endPoint, executor);
        this.bufferPool = bufferPool;
        this.session = session;
        this.bufferSize = bufferSize;
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
        producer.setInputBuffer(buffer);
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

    private int fill(EndPoint endPoint, ByteBuffer buffer)
    {
        try
        {
            if (endPoint.isInputShutdown())
                return -1;
            return endPoint.fill(buffer);
        }
        catch (IOException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not read from {}", endPoint, x);
            return -1;
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
        RetainableByteBuffer.Mutable networkBuffer = producer.networkBuffer;
        session.onData(new StreamData(frame, networkBuffer));
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

    protected class HTTP2Producer implements ExecutionStrategy.Producer
    {
        private final Callback fillableCallback = new FillableCallback();
        private RetainableByteBuffer.Mutable networkBuffer;
        private boolean shutdown;
        private boolean failed;

        private void setInputBuffer(ByteBuffer byteBuffer)
        {
            acquireNetworkBuffer();
            if (!networkBuffer.append(byteBuffer))
                LOG.warn("overflow");
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
            acquireNetworkBuffer();
            try
            {
                boolean parse = networkBuffer.hasRemaining();

                while (true)
                {
                    if (parse)
                    {
                        while (networkBuffer.hasRemaining())
                        {
                            session.getParser().parse(networkBuffer.getByteBuffer());
                            if (failed)
                                return null;
                        }

                        task = pollTask();
                        if (LOG.isDebugEnabled())
                            LOG.debug("Dequeued new task {}", task);
                        if (task != null)
                            return task;

                        // If more references than 1 (ie not just us), don't refill into buffer and risk compaction.
                        if (networkBuffer.isRetained())
                            reacquireNetworkBuffer();
                    }

                    // Here we know that this.networkBuffer is not retained by
                    // application code: either it has been released, or it's a new one.
                    int filled = fill(getEndPoint(), networkBuffer.getByteBuffer());
                    if (LOG.isDebugEnabled())
                        LOG.debug("Filled {} bytes in {}", filled, networkBuffer);

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
                        return null;
                    }
                }
            }
            finally
            {
                releaseNetworkBuffer();
                if (interested)
                    getEndPoint().fillInterested(fillableCallback);
            }
        }

        private void acquireNetworkBuffer()
        {
            if (networkBuffer == null)
            {
                networkBuffer = bufferPool.acquire(bufferSize, isUseInputDirectByteBuffers()).asMutable();
                if (LOG.isDebugEnabled())
                    LOG.debug("Acquired {}", networkBuffer);
            }
        }

        private void reacquireNetworkBuffer()
        {
            RetainableByteBuffer.Mutable currentBuffer = networkBuffer;
            if (currentBuffer == null)
                throw new IllegalStateException();

            if (currentBuffer.hasRemaining())
                throw new IllegalStateException();

            currentBuffer.release();
            networkBuffer = bufferPool.acquire(bufferSize, isUseInputDirectByteBuffers()).asMutable();
            if (LOG.isDebugEnabled())
                LOG.debug("Reacquired {}<-{}", currentBuffer, networkBuffer);
        }

        private void releaseNetworkBuffer()
        {
            RetainableByteBuffer.Mutable currentBuffer = networkBuffer;
            if (currentBuffer == null)
                throw new IllegalStateException();

            if (currentBuffer.hasRemaining() && !shutdown && !failed)
                throw new IllegalStateException();

            currentBuffer.release();
            networkBuffer = null;
            if (LOG.isDebugEnabled())
                LOG.debug("Released {}", currentBuffer);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x", getClass().getSimpleName(), hashCode());
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

    private static class StreamData extends Stream.Data
    {
        private final Retainable retainable;

        private StreamData(DataFrame frame, Retainable retainable)
        {
            super(frame);
            this.retainable = retainable;
        }

        @Override
        public boolean canRetain()
        {
            return retainable.canRetain();
        }

        @Override
        public boolean isRetained()
        {
            return retainable.isRetained();
        }

        @Override
        public void retain()
        {
            retainable.retain();
        }

        @Override
        public boolean release()
        {
            return retainable.release();
        }
    }
}
