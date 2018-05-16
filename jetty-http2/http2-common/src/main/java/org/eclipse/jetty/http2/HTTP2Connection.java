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
        private NetworkBuffer buffer;
        private boolean shutdown;

        private void setInputBuffer(ByteBuffer byteBuffer)
        {
            if (buffer == null)
                buffer = acquireNetworkBuffer();
            buffer.put(byteBuffer);
        }

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
                buffer = acquireNetworkBuffer();
            boolean parse = buffer.hasRemaining();
            while (true)
            {
                if (parse)
                {
                    buffer.retain();

                    while (buffer.hasRemaining())
                        parser.parse(buffer.buffer);

                    boolean released = buffer.tryRelease();

                    task = pollTask();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Dequeued new task {}", task);
                    if (task != null)
                    {
                        if (released)
                            releaseNetworkBuffer();
                        else
                            buffer = null;
                        return task;
                    }
                    else
                    {
                        if (!released)
                            buffer = acquireNetworkBuffer();
                    }
                }

                int filled = fill(getEndPoint(), buffer.buffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("Filled {} bytes in {}", filled, buffer);

                if (filled == 0)
                {
                    releaseNetworkBuffer();
                    getEndPoint().fillInterested(fillableCallback);
                    return null;
                }
                else if (filled < 0)
                {
                    releaseNetworkBuffer();
                    shutdown = true;
                    session.onShutdown();
                    return null;
                }
                else
                {
                    bytesIn.addAndGet(filled);
                    parse = true;
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
            if (!buffer.hasRemaining())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Released {}", buffer);
                buffer.release();
                byteBufferPool.release(buffer.buffer);
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
            return InvocationType.EITHER;
        }
    }

    private class ParserListener implements Parser.Listener
    {
        private final Parser.Listener listener;

        private ParserListener(Parser.Listener listener)
        {
            this.listener = listener;
        }

        @Override
        public void onData(DataFrame frame)
        {
            NetworkBuffer buffer = producer.buffer;
            buffer.retain();
            Callback callback = buffer;
            session.onData(frame, callback);
        }

        @Override
        public void onHeaders(HeadersFrame frame)
        {
            listener.onHeaders(frame);
        }

        @Override
        public void onPriority(PriorityFrame frame)
        {
            listener.onPriority(frame);
        }

        @Override
        public void onReset(ResetFrame frame)
        {
            listener.onReset(frame);
        }

        @Override
        public void onSettings(SettingsFrame frame)
        {
            listener.onSettings(frame);
        }

        @Override
        public void onPushPromise(PushPromiseFrame frame)
        {
            listener.onPushPromise(frame);
        }

        @Override
        public void onPing(PingFrame frame)
        {
            listener.onPing(frame);
        }

        @Override
        public void onGoAway(GoAwayFrame frame)
        {
            listener.onGoAway(frame);
        }

        @Override
        public void onWindowUpdate(WindowUpdateFrame frame)
        {
            listener.onWindowUpdate(frame);
        }

        @Override
        public void onConnectionFailure(int error, String reason)
        {
            listener.onConnectionFailure(error, reason);
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

        @Override
        public void succeeded()
        {
            release();
        }

        @Override
        public void failed(Throwable x)
        {
            release();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        private void release()
        {
            if (tryRelease())
            {
                byteBufferPool.release(buffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("Released retained {}", this);
            }
        }

        private boolean tryRelease()
        {
            return refCount.decrementAndGet() == 0;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), buffer);
        }
    }
}
