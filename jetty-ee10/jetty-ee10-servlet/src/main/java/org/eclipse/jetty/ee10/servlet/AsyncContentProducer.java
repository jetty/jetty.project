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

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StaticException;
import org.eclipse.jetty.util.component.Destroyable;
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
    private HttpInput.Interceptor _interceptor;
    private Content.Chunk _rawChunk;
    private Content.Chunk _transformedChunk;
    private boolean _error;
    private long _firstByteNanoTime = Long.MIN_VALUE;
    private long _rawBytesArrived;

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
        if (_rawChunk == null)
            _rawChunk = RECYCLED_ERROR_CHUNK;
        else if (!_rawChunk.isTerminal())
            throw new IllegalStateException("ContentProducer with unconsumed raw chunk cannot be recycled");

        if (_transformedChunk == null)
            _transformedChunk = RECYCLED_ERROR_CHUNK;
        else if (!_transformedChunk.isTerminal())
            throw new IllegalStateException("ContentProducer with unconsumed transformed chunk cannot be recycled");

        if (_interceptor instanceof Destroyable)
            ((Destroyable)_interceptor).destroy();
        _interceptor = null;
    }

    @Override
    public void reopen()
    {
        assertLocked();
        if (LOG.isDebugEnabled())
            LOG.debug("reopening {}", this);
        _rawChunk = null;
        _transformedChunk = null;
        _error = false;
        _firstByteNanoTime = Long.MIN_VALUE;
        _rawBytesArrived = 0L;
    }

    @Override
    public HttpInput.Interceptor getInterceptor()
    {
        assertLocked();
        return _interceptor;
    }

    @Override
    public void setInterceptor(HttpInput.Interceptor interceptor)
    {
        assertLocked();
        this._interceptor = interceptor;
    }

    @Override
    public int available()
    {
        assertLocked();
        Content.Chunk chunk = nextTransformedChunk();
        int available = chunk == null ? 0 : chunk.remaining();
        if (LOG.isDebugEnabled())
            LOG.debug("available = {} {}", available, this);
        return available;
    }

    @Override
    public boolean hasChunk()
    {
        assertLocked();
        boolean hasChunk = _rawChunk != null;
        if (LOG.isDebugEnabled())
            LOG.debug("hasChunk = {} {}", hasChunk, this);
        return hasChunk;
    }

    @Override
    public boolean isError()
    {
        assertLocked();
        if (LOG.isDebugEnabled())
            LOG.debug("isError = {} {}", _error, this);
        return _error;
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
                if (getRawBytesArrived() < minimumData)
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
    public long getRawBytesArrived()
    {
        assertLocked();
        if (LOG.isDebugEnabled())
            LOG.debug("getRawBytesArrived = {} {}", _rawBytesArrived, this);
        return _rawBytesArrived;
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
        if (_transformedChunk != null && !_transformedChunk.isTerminal())
        {
            if (_transformedChunk != _rawChunk)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("releasing current transformed chunk {}", this);
                _transformedChunk.skip(_transformedChunk.remaining());
                _transformedChunk.release();
            }
            _transformedChunk = null;
        }

        if (_rawChunk != null && !_rawChunk.isTerminal())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("releasing current raw chunk {}", this);
            _rawChunk.skip(_rawChunk.remaining());
            _rawChunk.release();
            _rawChunk = _rawChunk.isLast() ? Content.Chunk.EOF : null;
        }

        return _rawChunk != null && _rawChunk.isLast();
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
        Content.Chunk chunk = nextTransformedChunk();
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
        if (_transformedChunk == chunk)
        {
            chunk.release();
            if (_transformedChunk == _rawChunk)
                _rawChunk = null;
            _transformedChunk = null;
        }
    }

    @Override
    public boolean isReady()
    {
        assertLocked();
        Content.Chunk chunk = nextTransformedChunk();
        if (chunk != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("isReady(), got transformed chunk {} {}", chunk, this);
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

    private Content.Chunk nextTransformedChunk()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("nextTransformedChunk {}", this);

        while (true)
        {
            if (_transformedChunk != null)
            {
                if (_transformedChunk.isTerminal() || _transformedChunk.hasRemaining())
                {
                    if (_transformedChunk instanceof Content.Chunk.Error && !_error)
                    {
                        // In case the _rawChunk was set by consumeAvailable(), check the ServletChannel
                        // to see if it has a more precise error. Otherwise, the exact same
                        // terminal chunk will be returned by the ServletChannel; do not do that
                        // if the _error flag was set, meaning the current error is definitive.
                        Content.Chunk refreshedRawChunk = produceRawChunk();
                        if (refreshedRawChunk != null)
                            _rawChunk = _transformedChunk = refreshedRawChunk;
                        _error = _rawChunk instanceof Content.Chunk.Error;

                        if (LOG.isDebugEnabled())
                            LOG.debug("refreshed raw chunk: {} {}", _rawChunk, this);
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("transformed chunk not yet depleted, returning it {}", this);
                    return _transformedChunk;
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("current transformed chunk depleted {}", this);

                    _transformedChunk.release();
                    _transformedChunk = null;
                }
            }

            if (_rawChunk == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("producing new raw chunk {}", this);
                _rawChunk = produceRawChunk();
                if (_rawChunk == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("channel has no new raw chunk {}", this);
                    return null;
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("transforming raw chunk {}", this);
            transformRawChunk();
            if (_transformedChunk != null)
                _servletChannel.getState().onContentAdded();
        }
    }

    private void transformRawChunk()
    {
        assert _rawChunk != null;
        if (_interceptor != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("intercepting raw chunk {}", this);
            _transformedChunk = intercept();

            // If the interceptor generated a terminal chunk, _rawChunk must become that terminal chunk.
            if (_transformedChunk != null && _transformedChunk.isTerminal() && _transformedChunk != _rawChunk)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("interceptor generated a terminal chunk, _rawChunk must become that terminal chunk {}", this);
                _rawChunk.release();
                _rawChunk = _transformedChunk;
                return;
            }

            // If the interceptor generated a null chunk, release the raw chunk now if it is empty.
            if (_transformedChunk == null && !_rawChunk.hasRemaining() && !_rawChunk.isTerminal())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("interceptor generated a null chunk, releasing the empty raw chunk now {}", this);
                _rawChunk.release();
                _rawChunk = null;
                return;
            }

            // If the interceptor returned the raw chunk, release the raw chunk now if it is empty.
            if (_transformedChunk == _rawChunk && !_rawChunk.hasRemaining() && !_rawChunk.isTerminal())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("interceptor returned the raw chunk, releasing the empty raw chunk now {}", this);
                _rawChunk.release();
                _rawChunk = _transformedChunk = null;
            }
        }
        else
        {
            // Release the raw chunk now if it is empty.
            if (!_rawChunk.hasRemaining() && !_rawChunk.isTerminal())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("releasing the empty raw chunk now {}", this);
                _rawChunk.release();
                _rawChunk = null;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("no interceptor, transformed chunk is raw chunk {}", this);
            _transformedChunk = _rawChunk;
        }
    }

    private Content.Chunk intercept()
    {
        try
        {
            int remainingBeforeInterception = _rawChunk.remaining();
            Content.Chunk chunk = _interceptor.readFrom(_rawChunk);
            if (chunk != null && chunk.isTerminal() && !_rawChunk.isTerminal())
            {
                if (chunk instanceof Content.Chunk.Error errorChunk)
                {
                    // Set the _error flag to mark the chunk as definitive, i.e.:
                    // do not try to produce new raw chunk to get a fresher error
                    // when the terminal chunk was generated by the interceptor.
                    _error = true;
                    if (_servletChannel.getResponse().isCommitted())
                        _servletChannel.abort(errorChunk.getCause());
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("interceptor generated terminal chunk {}", this);
            }
            else if (chunk != _rawChunk && !_rawChunk.isTerminal() && _rawChunk.hasRemaining() && _rawChunk.remaining() == remainingBeforeInterception)
            {
                IOException failure = new IOException("Interceptor " + _interceptor + " did not consume any of the " + _rawChunk.remaining() + " remaining byte(s) of chunk");
                if (chunk != null)
                    chunk.release();
                consumeCurrentChunk();
                // Set the _error flag to mark the chunk as definitive, i.e.:
                // do not try to produce new raw chunk to get a fresher error
                // when the terminal chunk was caused by the interceptor not
                // consuming the raw chunk.
                _error = true;
                Response response = _servletChannel.getResponse();
                if (response.isCommitted())
                    _servletChannel.abort(failure);
                if (LOG.isDebugEnabled())
                    LOG.debug("interceptor did not consume chunk {}", this);
                chunk = _transformedChunk;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("intercepted raw chunk {}", this);
            return chunk;
        }
        catch (Throwable x)
        {
            IOException failure = new IOException("bad chunk", x);
            consumeCurrentChunk();
            // Set the _error flag to mark the chunk as definitive, i.e.:
            // do not try to produce new raw chunk to get a fresher error
            // when the terminal chunk was caused by the interceptor throwing.
            _error = true;
            Response response = _servletChannel.getResponse();
            if (response.isCommitted())
                _servletChannel.abort(failure);
            if (LOG.isDebugEnabled())
                LOG.debug("interceptor threw exception {}", this, x);
            return _transformedChunk;
        }
    }

    private Content.Chunk produceRawChunk()
    {
        Content.Chunk chunk = _servletChannel.getServletContextRequest().read();
        if (chunk != null)
        {
            _rawBytesArrived += chunk.remaining();
            if (_firstByteNanoTime == Long.MIN_VALUE)
                _firstByteNanoTime = NanoTime.now();
            if (LOG.isDebugEnabled())
                LOG.debug("produceRawChunk updated _rawBytesArrived to {} and _firstByteTimeStamp to {} {}", _rawBytesArrived, _firstByteNanoTime, this);
            // TODO: notify channel listeners (see ee9)?
            if (chunk instanceof Trailers trailers)
                _servletChannel.onTrailers(trailers.getTrailers());
        }
        if (LOG.isDebugEnabled())
            LOG.debug("produceRawChunk produced {} {}", chunk, this);
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
        return String.format("%s@%x[r=%s,t=%s,i=%s,error=%b]",
            getClass().getSimpleName(),
            hashCode(),
            _rawChunk,
            _transformedChunk,
            _interceptor,
            _error
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
