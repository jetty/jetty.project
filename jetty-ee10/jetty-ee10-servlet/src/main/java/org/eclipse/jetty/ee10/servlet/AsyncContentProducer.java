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

package org.eclipse.jetty.ee10.servlet;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StaticException;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non-blocking {@link ContentProducer} implementation. Calling {@link ContentProducer#nextChunk()} will never block
 * but will return null when there is no available content.
 */
class AsyncContentProducer implements ContentProducer
{
    private static final Logger LOG = LoggerFactory.getLogger(AsyncContentProducer.class);
    private static final Content.Chunk.Error RECYCLED_ERROR_CHUNK = Content.Chunk.from(new StaticException("ContentProducer has been recycled"));

    final AutoLock _lock;
    private final ServletChannel _servletChannel;
    private Content.Chunk _chunk;
    private long _firstByteNanoTime = Long.MIN_VALUE;
    private long _bytesArrived;

    /**
     * @param servletChannel The ServletChannel to produce input from.
     * @param lock The lock of the HttpInput, shared with this instance
     */
    AsyncContentProducer(ServletChannel servletChannel, AutoLock lock)
    {
        _servletChannel = servletChannel;
        _lock = lock;
    }

    @Override
    public void recycle()
    {
        assertLocked();
        if (LOG.isDebugEnabled())
            LOG.debug("recycling {}", this);

        // Make sure that the chunk has been fully consumed before destroying the interceptor and also make sure
        // that asking this instance for chunks between recycle and reopen will only produce error'ed chunks.
        if (_chunk == null)
            _chunk = RECYCLED_ERROR_CHUNK;
        else if (!_chunk.isTerminal())
            throw new IllegalStateException("ContentProducer with unconsumed chunk cannot be recycled");
    }

    @Override
    public void reopen()
    {
        assertLocked();
        if (LOG.isDebugEnabled())
            LOG.debug("reopening {}", this);
        _chunk = null;
        _firstByteNanoTime = Long.MIN_VALUE;
        _bytesArrived = 0L;
    }

    @Override
    public int available()
    {
        assertLocked();
        Content.Chunk chunk = produceChunk();
        int available = chunk == null ? 0 : chunk.remaining();
        if (LOG.isDebugEnabled())
            LOG.debug("available = {} {}", available, this);
        return available;
    }

    @Override
    public boolean hasChunk()
    {
        assertLocked();
        boolean hasChunk = _chunk != null;
        if (LOG.isDebugEnabled())
            LOG.debug("hasChunk = {} {}", hasChunk, this);
        return hasChunk;
    }

    @Override
    public boolean isError()
    {
        assertLocked();
        boolean error = _chunk instanceof Content.Chunk.Error;
        if (LOG.isDebugEnabled())
            LOG.debug("isError = {} {}", error, this);
        return error;
    }

    @Override
    public void checkMinDataRate()
    {
        assertLocked();
        long minRequestDataRate = _servletChannel.getHttpConfiguration().getMinRequestDataRate();
        if (LOG.isDebugEnabled())
            LOG.debug("checkMinDataRate [m={},t={}] {}", minRequestDataRate, _firstByteNanoTime, this);
        if (minRequestDataRate > 0 && _firstByteNanoTime != Long.MIN_VALUE)
        {
            long period = NanoTime.since(_firstByteNanoTime);
            if (period > 0)
            {
                long minimumData = minRequestDataRate * TimeUnit.NANOSECONDS.toMillis(period) / TimeUnit.SECONDS.toMillis(1);
                if (getBytesArrived() < minimumData)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("checkMinDataRate check failed {}", this);
                    BadMessageException bad = new BadMessageException(HttpStatus.REQUEST_TIMEOUT_408,
                        String.format("Request content data rate < %d B/s", minRequestDataRate));
                    if (_servletChannel.getState().isResponseCommitted())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("checkMinDataRate aborting channel {}", this);
                        _servletChannel.abort(bad);
                    }
                    consumeCurrentChunk();
                    throw bad;
                }
            }
        }
    }

    @Override
    public long getBytesArrived()
    {
        assertLocked();
        if (LOG.isDebugEnabled())
            LOG.debug("getBytesArrived = {} {}", _bytesArrived, this);
        return _bytesArrived;
    }

    @Override
    public boolean consumeAvailable()
    {
        assertLocked();

        boolean atEof = consumeCurrentChunk();
        if (LOG.isDebugEnabled())
            LOG.debug("consumed current chunk of ServletChannel EOF={} {}", atEof, this);
        if (atEof)
            return true;

        atEof = consumeAvailableChunks();
        if (LOG.isDebugEnabled())
            LOG.debug("consumed available chunks of ServletChannel EOF={} {}", atEof, this);
        return atEof;
    }

    private boolean consumeCurrentChunk()
    {
        if (_chunk != null && !_chunk.isTerminal())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("releasing current chunk {}", this);
            _chunk.skip(_chunk.remaining());
            _chunk.release();
            _chunk = _chunk.isLast() ? Content.Chunk.EOF : null;
        }

        return _chunk != null && _chunk.isLast();
    }

    private boolean consumeAvailableChunks()
    {
        ServletContextRequest request = _servletChannel.getServletContextRequest();
        while (true)
        {
            Content.Chunk chunk = request.read();
            if (chunk == null)
                return false;

            chunk.release();

            if (chunk.isTerminal())
                return chunk.isLast();
        }
    }

    @Override
    public boolean onContentProducible()
    {
        assertLocked();
        if (LOG.isDebugEnabled())
            LOG.debug("onContentProducible {}", this);
        return _servletChannel.getState().onReadReady();
    }

    @Override
    public Content.Chunk nextChunk()
    {
        assertLocked();
        Content.Chunk chunk = produceChunk();
        if (LOG.isDebugEnabled())
            LOG.debug("nextChunk = {} {}", chunk, this);
        if (chunk != null)
            _servletChannel.getState().onReadIdle();
        return chunk;
    }

    @Override
    public void reclaim(Content.Chunk chunk)
    {
        assertLocked();
        if (LOG.isDebugEnabled())
            LOG.debug("reclaim {} {}", chunk, this);
        assert chunk == _chunk;
        chunk.release();
        _chunk = null;
    }

    @Override
    public boolean isReady()
    {
        assertLocked();
        Content.Chunk chunk = produceChunk();
        if (chunk != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("isReady(), got chunk {} {}", chunk, this);
            return true;
        }

        _servletChannel.getState().onReadUnready();
        _servletChannel.getServletContextRequest().demand(() ->
        {
            if (_servletChannel.getHttpInput().onContentProducible())
                _servletChannel.handle();
        });

        if (LOG.isDebugEnabled())
            LOG.debug("isReady(), no chunk {}", this);
        return false;
    }

    boolean isUnready()
    {
        return _servletChannel.getState().isInputUnready();
    }

    private Content.Chunk produceChunk()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("produceChunk() {}", this);

        while (true)
        {
            if (_chunk != null)
            {
                if (_chunk.isTerminal() || _chunk.hasRemaining())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("chunk not yet depleted, returning it {}", this);
                    return _chunk;
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("current chunk depleted {}", this);

                    _chunk.release();
                    _chunk = null;
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("reading new chunk {}", this);
                _chunk = readChunk();
                if (_chunk == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("channel has no new chunk {}", this);
                    return null;
                }
                else
                {
                    _servletChannel.getState().onContentAdded();
                }
            }

            // Release the chunk immediately, if it is empty.
            if (_chunk != null && !_chunk.hasRemaining() && !_chunk.isTerminal())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("releasing empty chunk {}", this);
                _chunk.release();
                _chunk = null;
            }
        }
    }

    private Content.Chunk readChunk()
    {
        Content.Chunk chunk = _servletChannel.getServletContextRequest().read();
        if (chunk != null)
        {
            _bytesArrived += chunk.remaining();
            if (_firstByteNanoTime == Long.MIN_VALUE)
                _firstByteNanoTime = NanoTime.now();
            if (LOG.isDebugEnabled())
                LOG.debug("readChunk() updated _bytesArrived to {} and _firstByteTimeStamp to {} {}", _bytesArrived, _firstByteNanoTime, this);
            // TODO: notify channel listeners (see ee9)?
            if (chunk instanceof Trailers trailers)
                _servletChannel.onTrailers(trailers.getTrailers());
        }
        if (LOG.isDebugEnabled())
            LOG.debug("readChunk() produced {} {}", chunk, this);
        return chunk;
    }

    private void assertLocked()
    {
        if (!_lock.isHeldByCurrentThread())
            throw new IllegalStateException("ContentProducer must be called within lock scope");
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[c=%s]",
            getClass().getSimpleName(),
            hashCode(),
            _chunk
        );
    }

    LockedSemaphore newLockedSemaphore()
    {
        return new LockedSemaphore();
    }

    /**
     * A semaphore that assumes working under the same locked scope.
     */
    class LockedSemaphore
    {
        private final Condition _condition;
        private int _permits;

        private LockedSemaphore()
        {
            this._condition = _lock.newCondition();
        }

        void assertLocked()
        {
            if (!_lock.isHeldByCurrentThread())
                throw new IllegalStateException("LockedSemaphore must be called within lock scope");
        }

        void drainPermits()
        {
            _permits = 0;
        }

        void acquire() throws InterruptedException
        {
            while (_permits == 0)
                _condition.await();
            _permits--;
        }

        void release()
        {
            _permits++;
            _condition.signal();
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + " permits=" + _permits;
        }
    }
}
