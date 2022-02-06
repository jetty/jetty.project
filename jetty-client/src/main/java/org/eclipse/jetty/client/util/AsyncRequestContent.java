//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.util;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncRequestContent implements Request.Content, Request.Content.Subscription, Closeable
{
    private static final Logger LOG = LoggerFactory.getLogger(AsyncRequestContent.class);

    private final AutoLock lock = new AutoLock();
    private final Condition flush = lock.newCondition();
    private final Deque<Chunk> chunks = new ArrayDeque<>();
    private final String contentType;
    private long length = -1;
    private Consumer consumer;
    private boolean emitInitialContent;
    private int demand;
    private boolean stalled;
    private boolean committed;
    private boolean closed;
    private boolean terminated;
    private Throwable failure;

    public AsyncRequestContent(ByteBuffer... buffers)
    {
        this("application/octet-stream", buffers);
    }

    public AsyncRequestContent(String contentType, ByteBuffer... buffers)
    {
        this.contentType = contentType;
        Stream.of(buffers).forEach(this::offer);
    }

    @Override
    public String getContentType()
    {
        return contentType;
    }

    @Override
    public long getLength()
    {
        return length;
    }

    @Override
    public Subscription subscribe(Consumer consumer, boolean emitInitialContent)
    {
        try (AutoLock ignored = lock.lock())
        {
            if (this.consumer != null)
                throw new IllegalStateException("Multiple subscriptions not supported on " + this);
            this.consumer = consumer;
            this.emitInitialContent = emitInitialContent;
            this.stalled = true;
            if (closed)
                length = chunks.stream().mapToLong(chunk -> chunk.buffer.remaining()).sum();
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Content subscription for {}: {}", this, consumer);
        return this;
    }

    @Override
    public void demand()
    {
        boolean produce;
        try (AutoLock ignored = lock.lock())
        {
            ++demand;
            produce = stalled;
            if (stalled)
                stalled = false;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Content demand, producing {} for {}", produce, this);
        if (produce)
            produce();
    }

    @Override
    public void fail(Throwable failure)
    {
        List<Callback> toFail = List.of();
        try (AutoLock l = lock.lock())
        {
            if (this.failure == null)
            {
                this.failure = failure;
                // Transfer all chunks to fail them all.
                toFail = chunks.stream()
                    .map(chunk -> chunk.callback)
                    .collect(Collectors.toList());
                chunks.clear();
                flush.signal();
            }
        }
        toFail.forEach(c -> c.failed(failure));
    }

    public boolean offer(ByteBuffer buffer)
    {
        return offer(buffer, Callback.NOOP);
    }

    public boolean offer(ByteBuffer buffer, Callback callback)
    {
        return offer(new Chunk(buffer, callback));
    }

    private boolean offer(Chunk chunk)
    {
        boolean produce = false;
        Throwable failure;
        try (AutoLock ignored = lock.lock())
        {
            failure = this.failure;
            if (failure == null)
            {
                if (closed)
                {
                    failure = new IOException("closed");
                }
                else
                {
                    chunks.offer(chunk);
                    if (demand > 0)
                    {
                        if (stalled)
                        {
                            stalled = false;
                            produce = true;
                        }
                    }
                }
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Content offer {}, producing {} for {}", failure == null ? "succeeded" : "failed", produce, this, failure);
        if (failure != null)
        {
            chunk.callback.failed(failure);
            return false;
        }
        else if (produce)
        {
            produce();
        }
        return true;
    }

    private void produce()
    {
        while (true)
        {
            Throwable failure;
            try (AutoLock ignored = lock.lock())
            {
                failure = this.failure;
            }
            if (failure != null)
            {
                notifyFailure(consumer, failure);
                return;
            }

            try
            {
                Consumer consumer;
                Chunk chunk = Chunk.EMPTY;
                boolean lastContent = false;
                try (AutoLock ignored = lock.lock())
                {
                    if (terminated)
                        throw new EOFException("Demand after last content");
                    consumer = this.consumer;
                    if (committed || emitInitialContent)
                    {
                        chunk = chunks.poll();
                        lastContent = closed && chunks.isEmpty();
                        if (lastContent)
                            terminated = true;
                    }
                    if (chunk == null && (lastContent || !committed))
                        chunk = Chunk.EMPTY;
                    if (chunk == null)
                    {
                        stalled = true;
                    }
                    else
                    {
                        --demand;
                        committed = true;
                    }
                }
                if (chunk == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("No content, processing stalled for {}", this);
                    return;
                }

                notifyContent(consumer, chunk.buffer, lastContent, Callback.from(this::notifyFlush, chunk.callback));

                boolean noDemand;
                try (AutoLock ignored = lock.lock())
                {
                    noDemand = demand == 0;
                    if (noDemand)
                        stalled = true;
                }
                if (noDemand)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("No demand, processing stalled for {}", this);
                    return;
                }
            }
            catch (Throwable x)
            {
                // Fail and loop around to notify the failure.
                fail(x);
            }
        }
    }

    private void notifyContent(Consumer consumer, ByteBuffer buffer, boolean last, Callback callback)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Notifying content last={} {} for {}", last, BufferUtil.toDetailString(buffer), this);
            consumer.onContent(buffer, last, callback);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Failure while notifying content", x);
            callback.failed(x);
            fail(x);
        }
    }

    private void notifyFailure(Consumer consumer, Throwable failure)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Notifying failure for {}", this, failure);
            consumer.onFailure(failure);
        }
        catch (Throwable x)
        {
            LOG.trace("Failure while notifying content failure {}", failure, x);
        }
    }

    private void notifyFlush()
    {
        try (AutoLock l = lock.lock())
        {
            flush.signal();
        }
    }

    public void flush() throws IOException
    {
        try (AutoLock l = lock.lock())
        {
            try
            {
                while (true)
                {
                    // Always wrap the exception to make sure
                    // the stack trace comes from flush().
                    if (failure != null)
                        throw new IOException(failure);
                    if (chunks.isEmpty())
                        return;
                    flush.await();
                }
            }
            catch (InterruptedException x)
            {
                throw new InterruptedIOException();
            }
        }
    }

    @Override
    public void close()
    {
        boolean produce = false;
        try (AutoLock l = lock.lock())
        {
            if (closed)
                return;
            closed = true;
            if (demand > 0)
            {
                if (stalled)
                {
                    stalled = false;
                    produce = true;
                }
            }
            flush.signal();
        }
        if (produce)
            produce();
    }

    public boolean isClosed()
    {
        try (AutoLock ignored = lock.lock())
        {
            return closed;
        }
    }

    @Override
    public String toString()
    {
        int demand;
        boolean stalled;
        int chunks;
        try (AutoLock ignored = lock.lock())
        {
            demand = this.demand;
            stalled = this.stalled;
            chunks = this.chunks.size();
        }
        return String.format("%s@%x[demand=%d,stalled=%b,chunks=%d]", getClass().getSimpleName(), hashCode(), demand, stalled, chunks);
    }

    private static class Chunk
    {
        private static final Chunk EMPTY = new Chunk(BufferUtil.EMPTY_BUFFER, Callback.NOOP);

        private final ByteBuffer buffer;
        private final Callback callback;

        private Chunk(ByteBuffer buffer, Callback callback)
        {
            this.buffer = Objects.requireNonNull(buffer);
            this.callback = Objects.requireNonNull(callback);
        }
    }
}
