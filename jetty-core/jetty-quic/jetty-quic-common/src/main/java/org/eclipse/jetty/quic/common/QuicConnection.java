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

package org.eclipse.jetty.quic.common;

import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class QuicConnection extends AbstractConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicConnection.class);

    private final AutoLock lock = new AutoLock();
    private final AtomicLong bytesIn = new AtomicLong();
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final ByteBufferPool byteBufferPool;
    private final ExecutionStrategy strategy;
    private boolean useInputDirectByteBuffers = true;

    public QuicConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint)
    {
        super(endPoint, executor);
        this.byteBufferPool = byteBufferPool;
        this.strategy = new AdaptiveExecutionStrategy(new QuicProducer(), executor);
        LifeCycle.start(strategy);
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    @Override
    public long getBytesIn()
    {
        return bytesIn.get();
    }

    @Override
    public long getBytesOut()
    {
        // TODO
        return 0;
    }

    @Override
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("QUIC onFillable {} ", this);
        produce();
    }

    public void offerTask(Runnable task, boolean dispatch)
    {
        offerTask(task);
        if (dispatch)
            dispatch();
        else
            produce();
    }

    private Runnable pollTask()
    {
        try (AutoLock ignored = lock.lock())
        {
            return tasks.poll();
        }
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
            LOG.debug("QUIC produce {} ", this);
        strategy.produce();
    }

    protected void dispatch()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("QUIC dispatch {} ", this);
        strategy.dispatch();
    }

    private class QuicProducer implements ExecutionStrategy.Producer
    {
        private final Callback fillableCallback = new FillableCallback();

        @Override
        public Runnable produce()
        {
            Runnable task = pollTask();
            if (LOG.isDebugEnabled())
                LOG.debug("Dequeued task {}", task);
            if (task != null)
                return task;

            RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
            try
            {
                while (true)
                {
                    SocketAddress address = getEndPoint().receive(buffer.getByteBuffer());
                    int filled = address == EndPoint.EOF ? -1 : buffer.remaining();
                    if (LOG.isDebugEnabled())
                        LOG.debug("filled {} bytes from {} on {}", filled, address, getEndPoint());

                    if (filled > 0)
                    {
                        bytesIn.addAndGet(filled);
                        process(buffer);
                    }
                    else if (filled == 0)
                    {
                        buffer.release();
                        fillInterested(fillableCallback);
                        return null;
                    }
                    else
                    {
                        buffer.release();
                        return null;
                    }
                }
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.atDebug().setCause(x).log("failed to produce on {}", getEndPoint());
                buffer.release();
                // TODO
                // fail(x);
                return null;
            }
        }
    }

    protected abstract void process(RetainableByteBuffer buffer) throws Exception;

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
