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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StaticException;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non-blocking {@link ContentProducer} implementation. Calling {@link ContentProducer#nextContent()} will never block
 * but will return null when there is no available content.
 */
class AsyncContentProducer implements ContentProducer
{
    private static final Logger LOG = LoggerFactory.getLogger(AsyncContentProducer.class);
    private static final HttpInput.ErrorContent RECYCLED_ERROR_CONTENT = new HttpInput.ErrorContent(new StaticException("ContentProducer has been recycled"));
    private static final Throwable UNCONSUMED_CONTENT_EXCEPTION = new StaticException("Unconsumed content");

    private final AutoLock _lock = new AutoLock();
    private final HttpChannel _httpChannel;
    private HttpInput.Interceptor _interceptor;
    private HttpInput.Content _rawContent;
    private HttpInput.Content _transformedContent;
    private boolean _error;
    private long _firstByteNanoTime = Long.MIN_VALUE;
    private long _rawContentArrived;

    AsyncContentProducer(HttpChannel httpChannel)
    {
        _httpChannel = httpChannel;
    }

    @Override
    public AutoLock lock()
    {
        return _lock.lock();
    }

    @Override
    public void recycle()
    {
        assertLocked();
        if (LOG.isDebugEnabled())
            LOG.debug("recycling {}", this);

        // Make sure that the content has been fully consumed before destroying the interceptor and also make sure
        // that asking this instance for content between recycle and reopen will only produce error'ed content.
        if (_rawContent == null)
            _rawContent = RECYCLED_ERROR_CONTENT;
        else if (!_rawContent.isSpecial())
            throw new IllegalStateException("ContentProducer with unconsumed content cannot be recycled");

        if (_transformedContent == null)
            _transformedContent = RECYCLED_ERROR_CONTENT;
        else if (!_transformedContent.isSpecial())
            throw new IllegalStateException("ContentProducer with unconsumed content cannot be recycled");

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
        _rawContent = null;
        _transformedContent = null;
        _error = false;
        _firstByteNanoTime = Long.MIN_VALUE;
        _rawContentArrived = 0L;
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
        HttpInput.Content content = nextTransformedContent();
        int available = content == null ? 0 : content.remaining();
        if (LOG.isDebugEnabled())
            LOG.debug("available = {} {}", available, this);
        return available;
    }

    @Override
    public boolean hasContent()
    {
        assertLocked();
        boolean hasContent = _rawContent != null;
        if (LOG.isDebugEnabled())
            LOG.debug("hasContent = {} {}", hasContent, this);
        return hasContent;
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
        long minRequestDataRate = _httpChannel.getHttpConfiguration().getMinRequestDataRate();
        if (LOG.isDebugEnabled())
            LOG.debug("checkMinDataRate [m={},t={}] {}", minRequestDataRate, _firstByteNanoTime, this);
        if (minRequestDataRate > 0 && _firstByteNanoTime != Long.MIN_VALUE)
        {
            long period = NanoTime.since(_firstByteNanoTime);
            if (period > 0)
            {
                long minimumData = minRequestDataRate * TimeUnit.NANOSECONDS.toMillis(period) / TimeUnit.SECONDS.toMillis(1);
                if (getRawContentArrived() < minimumData)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("checkMinDataRate check failed {}", this);
                    BadMessageException bad = new BadMessageException(HttpStatus.REQUEST_TIMEOUT_408,
                        String.format("Request content data rate < %d B/s", minRequestDataRate));
                    if (_httpChannel.getState().isResponseCommitted())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("checkMinDataRate aborting channel {}", this);
                        _httpChannel.abort(bad);
                    }
                    failCurrentContent(bad);
                    throw bad;
                }
            }
        }
    }

    @Override
    public long getRawContentArrived()
    {
        assertLocked();
        if (LOG.isDebugEnabled())
            LOG.debug("getRawContentArrived = {} {}", _rawContentArrived, this);
        return _rawContentArrived;
    }

    @Override
    public boolean consumeAll()
    {
        assertLocked();
        Throwable x = UNCONSUMED_CONTENT_EXCEPTION;
        if (LOG.isTraceEnabled())
        {
            x = new StaticException("Unconsumed content", true);
            LOG.trace("consumeAll {}", this, x);
        }
        failCurrentContent(x);
        // A specific HttpChannel mechanism must be used as the following code
        // does not guarantee that the channel will synchronously deliver all
        // content it already contains:
        //   while (true)
        //   {
        //       HttpInput.Content content = _httpChannel.produceContent();
        //       ...
        //   }
        // as the HttpChannel's produceContent() contract makes no such promise;
        // for instance the H2 implementation calls Stream.demand() that may
        // deliver the content asynchronously. Tests in StreamResetTest cover this.
        boolean atEof = _httpChannel.failAllContent(x);
        if (LOG.isDebugEnabled())
            LOG.debug("failed all content of http channel EOF={} {}", atEof, this);
        return atEof;
    }

    private void failCurrentContent(Throwable x)
    {
        if (_transformedContent != null && !_transformedContent.isSpecial())
        {
            if (_transformedContent != _rawContent)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failing currently held transformed content {} {}", x, this);
                _transformedContent.skip(_transformedContent.remaining());
                _transformedContent.failed(x);
            }
            _transformedContent = null;
        }

        if (_rawContent != null && !_rawContent.isSpecial())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failing currently held raw content {} {}", x, this);
            _rawContent.skip(_rawContent.remaining());
            _rawContent.failed(x);
            _rawContent = null;
        }

        HttpInput.ErrorContent errorContent = new HttpInput.ErrorContent(x);
        _transformedContent = errorContent;
        _rawContent = errorContent;
    }

    @Override
    public boolean onContentProducible()
    {
        assertLocked();
        if (LOG.isDebugEnabled())
            LOG.debug("onContentProducible {}", this);
        return _httpChannel.getState().onReadReady();
    }

    @Override
    public HttpInput.Content nextContent()
    {
        assertLocked();
        HttpInput.Content content = nextTransformedContent();
        if (LOG.isDebugEnabled())
            LOG.debug("nextContent = {} {}", content, this);
        if (content != null)
            _httpChannel.getState().onReadIdle();
        return content;
    }

    @Override
    public void reclaim(HttpInput.Content content)
    {
        assertLocked();
        if (LOG.isDebugEnabled())
            LOG.debug("reclaim {} {}", content, this);
        if (_transformedContent == content)
        {
            content.succeeded();
            if (_transformedContent == _rawContent)
                _rawContent = null;
            _transformedContent = null;
        }
    }

    @Override
    public boolean isReady()
    {
        assertLocked();
        HttpInput.Content content = nextTransformedContent();
        if (content != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("isReady(), got transformed content {} {}", content, this);
            return true;
        }

        _httpChannel.getState().onReadUnready();
        while (_httpChannel.needContent())
        {
            content = nextTransformedContent();
            if (LOG.isDebugEnabled())
                LOG.debug("isReady(), got transformed content after needContent retry {} {}", content, this);
            if (content != null)
            {
                _httpChannel.getState().onContentAdded();
                return true;
            }
            else
            {
                // We could have read some rawContent but not enough to generate
                // transformed content, so we need to call needContent() again
                // to tell the channel that more content is needed.
                if (LOG.isDebugEnabled())
                    LOG.debug("isReady(), could not transform content after needContent retry {}", this);
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("isReady(), no content for needContent retry {}", this);
        return false;
    }

    boolean isUnready()
    {
        return _httpChannel.getState().isInputUnready();
    }

    private HttpInput.Content nextTransformedContent()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("nextTransformedContent {}", this);

        while (true)
        {
            if (_transformedContent != null)
            {
                if (_transformedContent.isSpecial() || !_transformedContent.isEmpty())
                {
                    if (_transformedContent.getError() != null && !_error)
                    {
                        // In case the _rawContent was set by consumeAll(), check the httpChannel
                        // to see if it has a more precise error. Otherwise, the exact same
                        // special content will be returned by the httpChannel; do not do that
                        // if the _error flag was set, meaning the current error is definitive.
                        HttpInput.Content refreshedRawContent = produceRawContent();
                        if (refreshedRawContent != null)
                            _rawContent = _transformedContent = refreshedRawContent;
                        _error = _rawContent.getError() != null;
                        if (LOG.isDebugEnabled())
                            LOG.debug("refreshed raw content: {} {}", _rawContent, this);
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("transformed content not yet depleted, returning it {}", this);
                    return _transformedContent;
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("current transformed content depleted {}", this);
                    _transformedContent.succeeded();
                    _transformedContent = null;
                }
            }

            if (_rawContent == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("producing new raw content {}", this);
                _rawContent = produceRawContent();
                if (_rawContent == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("channel has no new raw content {}", this);
                    return null;
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("transforming raw content {}", this);
            transformRawContent();
        }
    }

    private void transformRawContent()
    {
        if (_interceptor != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("intercepting raw content {}", this);
            _transformedContent = intercept();

            // If the interceptor generated a special content, _rawContent must become that special content.
            if (_transformedContent != null && _transformedContent.isSpecial() && _transformedContent != _rawContent)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("interceptor generated a special content, _rawContent must become that special content {}", this);
                _rawContent.succeeded();
                _rawContent = _transformedContent;
                return;
            }

            // If the interceptor generated a null content, recycle the raw content now if it is empty.
            if (_transformedContent == null && _rawContent.isEmpty() && !_rawContent.isSpecial())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("interceptor generated a null content, recycling the empty raw content now {}", this);
                _rawContent.succeeded();
                _rawContent = null;
                return;
            }

            // If the interceptor returned the raw content, recycle the raw content now if it is empty.
            if (_transformedContent == _rawContent && _rawContent.isEmpty() && !_rawContent.isSpecial())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("interceptor returned the raw content, recycle the empty raw content now {}", this);
                _rawContent.succeeded();
                _rawContent = _transformedContent = null;
            }
        }
        else
        {
            // Recycle the raw content now if it is empty.
            if (_rawContent.isEmpty() && !_rawContent.isSpecial())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("recycling the empty raw content now {}", this);
                _rawContent.succeeded();
                _rawContent = null;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("no interceptor, transformed content is raw content {}", this);
            _transformedContent = _rawContent;
        }
    }

    private HttpInput.Content intercept()
    {
        try
        {
            int remainingBeforeInterception = _rawContent.remaining();
            HttpInput.Content content = _interceptor.readFrom(_rawContent);
            if (content != null && content.isSpecial() && !_rawContent.isSpecial())
            {
                Throwable error = content.getError();
                if (error != null)
                {
                    // Set the _error flag to mark the content as definitive, i.e.:
                    // do not try to produce new raw content to get a fresher error
                    // when the special content was generated by the interceptor.
                    _error = true;
                    if (_httpChannel.getResponse().isCommitted())
                        _httpChannel.abort(error);
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("interceptor generated special content {}", this);
            }
            else if (content != _rawContent && !_rawContent.isSpecial() && !_rawContent.isEmpty() && _rawContent.remaining() == remainingBeforeInterception)
            {
                IOException failure = new IOException("Interceptor " + _interceptor + " did not consume any of the " + _rawContent.remaining() + " remaining byte(s) of content");
                if (content != null)
                    content.failed(failure);
                failCurrentContent(failure);
                // Set the _error flag to mark the content as definitive, i.e.:
                // do not try to produce new raw content to get a fresher error
                // when the special content was caused by the interceptor not
                // consuming the raw content.
                _error = true;
                Response response = _httpChannel.getResponse();
                if (response.isCommitted())
                    _httpChannel.abort(failure);
                if (LOG.isDebugEnabled())
                    LOG.debug("interceptor did not consume content {}", this);
                content = _transformedContent;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("intercepted raw content {}", this);
            return content;
        }
        catch (Throwable x)
        {
            IOException failure = new IOException("Bad content", x);
            failCurrentContent(failure);
            // Set the _error flag to mark the content as definitive, i.e.:
            // do not try to produce new raw content to get a fresher error
            // when the special content was caused by the interceptor throwing.
            _error = true;
            Response response = _httpChannel.getResponse();
            if (response.isCommitted())
                _httpChannel.abort(failure);
            if (LOG.isDebugEnabled())
                LOG.debug("interceptor threw exception {}", this, x);
            return _transformedContent;
        }
    }

    private HttpInput.Content produceRawContent()
    {
        HttpInput.Content content = _httpChannel.produceContent();
        if (content != null)
        {
            _rawContentArrived += content.remaining();
            if (_firstByteNanoTime == Long.MIN_VALUE)
                _firstByteNanoTime = NanoTime.now();
            if (LOG.isDebugEnabled())
                LOG.debug("produceRawContent updated rawContentArrived to {} and firstByteTimeStamp to {} {}", _rawContentArrived, _firstByteNanoTime, this);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("produceRawContent produced {} {}", content, this);
        return content;
    }

    private void assertLocked()
    {
        if (!_lock.isHeldByCurrentThread())
            throw new IllegalStateException("ContentProducer must be called within lock scope");
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[r=%s,t=%s,i=%s,error=%b,c=%s]",
            getClass().getSimpleName(),
            hashCode(),
            _rawContent,
            _transformedContent,
            _interceptor,
            _error,
            _httpChannel
        );
    }

    LockedSemaphore newLockedSemaphore()
    {
        return new LockedSemaphore();
    }

    /**
     * A semaphore that assumes working under {@link AsyncContentProducer#lock()} scope.
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
