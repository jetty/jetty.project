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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.TryExecutor;
import org.eclipse.jetty.util.thread.strategy.EatWhatYouKill;

public class HTTP2Connection extends AbstractConnection implements WriteFlusher.Listener
{
    protected static final Logger LOG = Log.getLogger(HTTP2Connection.class);
    
    // TODO remove this once we are sure EWYK is OK for http2
    private static final boolean PEC_MODE = Boolean.getBoolean("org.eclipse.jetty.http2.PEC_MODE");
    
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
        if (PEC_MODE)
            executor = new TryExecutor.NoTryExecutor(executor);
        this.strategy = new EatWhatYouKill(producer, executor);
        LifeCycle.start(strategy);
        parser.init(ParserListener::new);
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
        if (buffer != null)
            producer.setInputBuffer(buffer);
    }

    @Override
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Open {} ", this);
        super.onOpen();
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
            dispatch();
        else
            produce();
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
            if (networkBuffer == null)
                networkBuffer = acquireNetworkBuffer();
            networkBuffer.put(byteBuffer);
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

            if (networkBuffer == null)
                networkBuffer = acquireNetworkBuffer();

            boolean parse = networkBuffer.hasRemaining();

            while (true)
            {
                if (parse)
                {
                    boolean released;
                    networkBuffer.retain();
                    try
                    {
                        while (networkBuffer.hasRemaining())
                        {
                            parser.parse(networkBuffer.buffer);
                            if (failed)
                                return null;
                        }
                    }
                    finally
                    {
                        released = networkBuffer.release();
                        if (failed && released)
                            releaseNetworkBuffer();
                    }

                    task = pollTask();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Dequeued new task {}", task);
                    if (task != null)
                    {
                        if (released)
                            releaseNetworkBuffer();
                        else
                            networkBuffer = null;
                        return task;
                    }
                    else
                    {
                        if (!released)
                            networkBuffer = acquireNetworkBuffer();
                    }
                }

                // Here we know that this.buffer is not retained:
                // either it has been released, or it's a new one.

                int filled = fill(getEndPoint(), networkBuffer.buffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("Filled {} bytes in {}", filled, networkBuffer);

                if (filled > 0)
                {
                    bytesIn.addAndGet(filled);
                    parse = true;
                }
                else if (filled == 0)
                {
                    releaseNetworkBuffer();
                    getEndPoint().fillInterested(fillableCallback);
                    return null;
                }
                else
                {
                    releaseNetworkBuffer();
                    shutdown = true;
                    session.onShutdown();
                    return null;
                }
            }
        }

        private NetworkBuffer acquireNetworkBuffer()
        {
            NetworkBuffer networkBuffer = new NetworkBuffer();
            if (LOG.isDebugEnabled())
                LOG.debug("Acquired {}", networkBuffer);
            return networkBuffer;
        }

        private void releaseNetworkBuffer()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Released {}", networkBuffer);
            networkBuffer.recycle();
            networkBuffer = null;
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

    private class NetworkBuffer implements Callback, Retainable
    {
        private final AtomicInteger refCount = new AtomicInteger();
        private final ByteBuffer buffer;

        private NetworkBuffer()
        {
            buffer = byteBufferPool.acquire(bufferSize, false); // TODO: make directness customizable
        }

        private void put(ByteBuffer source)
        {
            BufferUtil.append(buffer, source);
        }

        private boolean hasRemaining()
        {
            return buffer.hasRemaining();
        }

        @Override
        public void retain()
        {
            refCount.incrementAndGet();
        }

        private boolean release()
        {
            return refCount.decrementAndGet() == 0;
        }

        @Override
        public void succeeded()
        {
            if (release())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Released retained {}", this);
                recycle();
            }
        }

        @Override
        public void failed(Throwable failure)
        {
            if (release())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Released retained " + this, failure);
                recycle();
            }
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        private void recycle()
        {
            byteBufferPool.release(buffer);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), buffer);
        }
    }
}
