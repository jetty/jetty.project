//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.TryExecutor;
import org.eclipse.jetty.util.thread.strategy.EatWhatYouKill;

public class HTTP2Connection extends AbstractConnection implements WriteFlusher.Listener
{
    protected static final Logger LOG = Log.getLogger(HTTP2Connection.class);

    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final HTTP2Producer producer = new HTTP2Producer();
    private final AtomicLong bytesIn = new AtomicLong();
    private final ByteBufferPool byteBufferPool;
    private final Parser parser;
    private final ISession session;
    private final int bufferSize;
    private final ExecutionStrategy strategy;

    public HTTP2Connection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, Parser parser, ISession session, int bufferSize)
    {
        super(endPoint, executor);
        this.byteBufferPool = byteBufferPool;
        this.parser = parser;
        this.session = session;
        this.bufferSize = bufferSize;
        // TODO HTTP2 cannot use EWYK without fix for #1803
        this.strategy = new EatWhatYouKill(producer, new TryExecutor.NoTryExecutor(executor));
        LifeCycle.start(strategy);
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

    protected void setInputBuffer(ByteBuffer buffer)
    {
        producer.buffer = buffer;
    }

    @Override
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Open {} ", this);
        super.onOpen();
        strategy.produce();
    }

    @Override
    public void onClose()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Close {} ", this);
        super.onClose();

        LifeCycle.stop(strategy);
    }

    @Override
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 onFillable {} ", this);
        strategy.produce();
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
            LOG.debug("Could not read from " + endPoint, x);
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
            strategy.dispatch();
        else
            strategy.produce();
    }

    @Override
    public void close()
    {
        // We don't call super from here, otherwise we close the
        // endPoint and we're not able to read or write anymore.
        session.close(ErrorCode.NO_ERROR.code, "close", Callback.NOOP);
    }

    private void offerTask(Runnable task)
    {
        synchronized (this)
        {
            tasks.offer(task);
        }
    }

    private Runnable pollTask()
    {
        synchronized (this)
        {
            return tasks.poll();
        }
    }

    @Override
    public void onFlushed(long bytes) throws IOException
    {
        // TODO: add method to ISession ?
        ((HTTP2Session)session).onFlushed(bytes);
    }

    protected class HTTP2Producer implements ExecutionStrategy.Producer
    {
        private final Callback fillableCallback = new FillableCallback();
        private ByteBuffer buffer;
        private boolean shutdown;

        @Override
        public Runnable produce()
        {
            Runnable task = pollTask();
            if (LOG.isDebugEnabled())
                LOG.debug("Dequeued task {}", task);
            if (task != null)
                return task;

            if (isFillInterested() || shutdown)
                return null;

            if (buffer == null)
                buffer = byteBufferPool.acquire(bufferSize, false); // TODO: make directness customizable
            boolean looping = BufferUtil.hasContent(buffer);
            while (true)
            {
                if (looping)
                {
                    while (buffer.hasRemaining())
                        parser.parse(buffer);

                    task = pollTask();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Dequeued new task {}", task);
                    if (task != null)
                    {
                        release();
                        return task;
                    }
                }

                int filled = fill(getEndPoint(), buffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("Filled {} bytes", filled);

                if (filled == 0)
                {
                    release();
                    getEndPoint().fillInterested(fillableCallback);
                    return null;
                }
                else if (filled < 0)
                {
                    release();
                    shutdown = true;
                    session.onShutdown();
                    return null;
                }
                else
                {
                    bytesIn.addAndGet(filled);
                }

                looping = true;
            }
        }

        private void release()
        {
            if (buffer != null && !buffer.hasRemaining())
            {
                byteBufferPool.release(buffer);
                buffer = null;
            }
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
            // TODO: see also AbstractHTTP2ServerConnectionFactory.reservedThreads.
            // TODO: it's non blocking here because reservedThreads=0.
            return InvocationType.NON_BLOCKING;
        }
    }
}
