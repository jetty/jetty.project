//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Connection extends AbstractConnection implements WriteFlusher.Listener, Connection.UpgradeTo
{
    protected static final Logger LOG = LoggerFactory.getLogger(HTTP2Connection.class);

    private final AutoLock lock = new AutoLock();
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final HTTP2Producer producer = new HTTP2Producer();
    private final AtomicLong bytesIn = new AtomicLong();
    private final RetainableByteBufferPool retainableByteBufferPool;
    private final Parser parser;
    private final ISession session;
    private final int bufferSize;
    private final ExecutionStrategy strategy;
    private boolean useInputDirectByteBuffers;
    private boolean useOutputDirectByteBuffers;

    protected HTTP2Connection(RetainableByteBufferPool retainableByteBufferPool, Executor executor, EndPoint endPoint, Parser parser, ISession session, int bufferSize)
    {
        super(endPoint, executor);
        this.retainableByteBufferPool = retainableByteBufferPool;
        this.parser = parser;
        this.session = session;
        this.bufferSize = bufferSize;
        this.strategy = new AdaptiveExecutionStrategy(producer, executor);
        LifeCycle.start(strategy);
        parser.init(ParserListener::new);
    }

    @Override
    public long getMessagesIn()
    {
        HTTP2Session session = (HTTP2Session)getSession();
        return session.getStreamsOpened();
    }

    @Override
    public long getMessagesOut()
    {
        HTTP2Session session = (HTTP2Session)getSession();
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

    public ISession getSession()
    {
        return session;
    }

    protected Parser getParser()
    {
        return parser;
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
    public boolean onIdleExpired()
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

    protected void offerTask(Runnable task, boolean dispatch)
    {
        offerTask(task);
        if (dispatch)
            dispatch();
        else
            produce();
    }

    private void offerTask(Runnable task)
    {
        try (AutoLock l = lock.lock())
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
        try (AutoLock l = lock.lock())
        {
            return tasks.poll();
        }
    }

    @Override
    public void onFlushed(long bytes) throws IOException
    {
        session.onFlushed(bytes);
    }

    protected class HTTP2Producer implements ExecutionStrategy.Producer
    {
        private final Callback fillableCallback = new FillableCallback();
        private NetworkBuffer networkBuffer;
        private boolean shutdown;
        private boolean failed;

        private void setInputBuffer(ByteBuffer byteBuffer)
        {
            acquireNetworkBuffer();
            // TODO handle buffer overflow?
            networkBuffer.put(byteBuffer);
        }

        @Override
        public Runnable produce()
        {
            Runnable task = pollTask();
            if (LOG.isDebugEnabled())
                LOG.debug("Dequeued task {}", String.valueOf(task));
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
                            parser.parse(networkBuffer.getBuffer());
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
                    int filled = fill(getEndPoint(), networkBuffer.getBuffer());
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
                networkBuffer = new NetworkBuffer();
                if (LOG.isDebugEnabled())
                    LOG.debug("Acquired {}", networkBuffer);
            }
        }

        private void reacquireNetworkBuffer()
        {
            NetworkBuffer currentBuffer = networkBuffer;
            if (currentBuffer == null)
                throw new IllegalStateException();

            if (currentBuffer.hasRemaining())
                throw new IllegalStateException();

            currentBuffer.release();
            networkBuffer = new NetworkBuffer();
            if (LOG.isDebugEnabled())
                LOG.debug("Reacquired {}<-{}", currentBuffer, networkBuffer);
        }

        private void releaseNetworkBuffer()
        {
            NetworkBuffer currentBuffer = networkBuffer;
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

    private class ParserListener extends Parser.Listener.Wrapper
    {
        private ParserListener(Parser.Listener listener)
        {
            super(listener);
        }

        @Override
        public void onData(DataFrame frame)
        {
            NetworkBuffer networkBuffer = producer.networkBuffer;
            networkBuffer.retain();
            Callback callback = networkBuffer;
            session.onData(frame, callback);
        }

        @Override
        public void onConnectionFailure(int error, String reason)
        {
            producer.failed = true;
            super.onConnectionFailure(error, reason);
        }
    }

    private class NetworkBuffer implements Callback
    {
        private final RetainableByteBuffer delegate;

        private NetworkBuffer()
        {
            delegate = retainableByteBufferPool.acquire(bufferSize, isUseInputDirectByteBuffers());
        }

        public ByteBuffer getBuffer()
        {
            return delegate.getBuffer();
        }

        public boolean isRetained()
        {
            return delegate.isRetained();
        }

        public boolean hasRemaining()
        {
            return delegate.hasRemaining();
        }

        public boolean release()
        {
            return delegate.release();
        }

        public void retain()
        {
            delegate.retain();
        }

        private void put(ByteBuffer source)
        {
            BufferUtil.append(delegate.getBuffer(), source);
        }

        @Override
        public void succeeded()
        {
            completed(null);
        }

        @Override
        public void failed(Throwable failure)
        {
            completed(failure);
        }

        private void completed(Throwable failure)
        {
            if (delegate.release())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Released retained {}", this, failure);
            }
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }
    }
}
