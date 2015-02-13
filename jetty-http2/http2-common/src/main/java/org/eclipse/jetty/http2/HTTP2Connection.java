//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.util.Queue;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ConcurrentArrayQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutionStrategy;

public class HTTP2Connection extends AbstractConnection implements Connection.UpgradeTo
{
    protected static final Logger LOG = Log.getLogger(HTTP2Connection.class);

    private final Queue<Runnable> tasks = new ConcurrentArrayQueue<>();
    private final ByteBufferPool byteBufferPool;
    private final Parser parser;
    private final ISession session;
    private final int bufferSize;
    private final HTTP2Producer producer = new HTTP2Producer();
    private final ExecutionStrategy executionStrategy;

    public HTTP2Connection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, Parser parser, ISession session, int bufferSize)
    {
        super(endPoint, executor);
        this.byteBufferPool = byteBufferPool;
        this.parser = parser;
        this.session = session;
        this.bufferSize = bufferSize;
        this.executionStrategy = ExecutionStrategy.Factory.instanceFor(producer, executor);
    }

    protected ISession getSession()
    {
        return session;
    }

    public void onUpgradeTo(ByteBuffer prefilled)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 onUpgradeTo {} {}", this, BufferUtil.toDebugString(prefilled));
        producer.prefill(prefilled);
    }
    
    @Override
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Open {} ", this);
        super.onOpen();
        executionStrategy.execute();
    }

    @Override
    public void onClose()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Close {} ", this);
        super.onClose();
    }

    @Override
    public void onFillable()
    {
        executionStrategy.execute();
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
    protected boolean onReadTimeout()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Idle timeout {}ms expired on {}", getEndPoint().getIdleTimeout(), this);
        session.onIdleTimeout();
        return false;
    }

    protected void offerTask(Runnable task)
    {
        tasks.offer(task);
    }

    private class HTTP2Producer implements ExecutionStrategy.Producer
    {
        private ByteBuffer buffer;

        @Override
        public Runnable produce()
        {
            Runnable task = tasks.poll();
            if (LOG.isDebugEnabled())
                LOG.debug("Dequeued task {}", task);
            if (task != null)
                return task;

            boolean looping = BufferUtil.hasContent(buffer);
            while (true)
            {
                if (buffer == null)
                    buffer = byteBufferPool.acquire(bufferSize, false);

                if (looping)
                {
                    while (buffer.hasRemaining())
                        parser.parse(buffer);

                    task = tasks.poll();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Dequeued task {}", task);
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
                    fillInterested();
                    return null;
                }
                else if (filled < 0)
                {
                    release();
                    session.onShutdown();
                    return null;
                }

                looping = true;
            }
        }

        public void prefill(ByteBuffer prefilledBuffer)
        {
            buffer=prefilledBuffer;
        }

        private void release()
        {
            if (BufferUtil.isEmpty(buffer))
            {
                byteBufferPool.release(buffer);
                buffer = null;
            }
        }
    }
}
