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

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class QuicConnection extends AbstractConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicConnection.class);

    private final AutoLock lock = new AutoLock();
    private final AtomicLong bytesOut = new AtomicLong();
    private final Callback fillableCallback = new FillableCallback();
    private final Queue<Invocable.Task> tasks = new ArrayDeque<>();
    private final Scheduler scheduler;
    private final ByteBufferPool byteBufferPool;
    private final AdaptiveExecutionStrategy strategy;
    private boolean useInputDirectByteBuffers = true;

    public QuicConnection(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, EndPoint endPoint)
    {
        super(endPoint, executor);
        this.scheduler = scheduler;
        this.byteBufferPool = byteBufferPool;
        this.strategy = new AdaptiveExecutionStrategy(this::produce, getExecutor());
    }

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
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
    public void onOpen()
    {
        super.onOpen();
        LifeCycle.start(strategy);
        fillInterested();
    }

    @Override
    public void onClose(Throwable cause)
    {
        LifeCycle.stop(strategy);
        super.onClose(cause);
    }

    @Override
    public void fillInterested()
    {
        fillInterested(fillableCallback);
    }

    @Override
    public void onFillable()
    {
        strategy.produce();
    }

    void bytesWritten(long bytesWritten)
    {
        bytesOut.addAndGet(bytesWritten);
    }

    @Override
    public long getBytesOut()
    {
        return bytesOut.get();
    }

    private Runnable produce()
    {
        Invocable.Task task = pollTask();
        if (LOG.isDebugEnabled())
            LOG.debug("produced task {} on {}", task, this);
        if (task != null)
            return task;

        boolean interested = isFillInterested();
        if (LOG.isDebugEnabled())
            LOG.debug("producing fillInterested={} on {}", interested, this);
        if (interested)
            return null;

        return doProduce();
    }

    protected abstract Runnable doProduce();

    protected abstract void terminate(QuicSession session);

    void offerTask(Invocable.Task task, boolean dispatch)
    {
        try (var _ = lock.lock())
        {
            tasks.offer(task);
        }
        if (dispatch)
            strategy.dispatch();
        else
            strategy.produce();
    }

    protected Invocable.Task pollTask()
    {
        try (var _ = lock.lock())
        {
            return tasks.poll();
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
