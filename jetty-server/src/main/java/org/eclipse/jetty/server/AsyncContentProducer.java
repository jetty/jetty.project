//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non-blocking {@link ContentProducer} implementation. Calling {@link #nextContent()} will never block
 * but will return null when there is no available content.
 */
class AsyncContentProducer implements ContentProducer
{
    private static final Logger LOG = LoggerFactory.getLogger(AsyncContentProducer.class);

    private final HttpChannel _httpChannel;
    private HttpInput.Interceptor _interceptor;
    private HttpInput.Content _rawContent;
    private HttpInput.Content _transformedContent;
    private boolean _error;
    private long _firstByteTimeStamp = Long.MIN_VALUE;
    private long _rawContentArrived;

    AsyncContentProducer(HttpChannel httpChannel)
    {
        _httpChannel = httpChannel;
    }

    @Override
    public void recycle()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("recycling {}", this);
        _interceptor = null;
        _rawContent = null;
        _transformedContent = null;
        _error = false;
        _firstByteTimeStamp = Long.MIN_VALUE;
        _rawContentArrived = 0L;
    }

    @Override
    public HttpInput.Interceptor getInterceptor()
    {
        return _interceptor;
    }

    @Override
    public void setInterceptor(HttpInput.Interceptor interceptor)
    {
        this._interceptor = interceptor;
    }

    @Override
    public int available()
    {
        HttpInput.Content content = nextTransformedContent();
        int available = content == null ? 0 : content.remaining();
        if (LOG.isDebugEnabled())
            LOG.debug("available = {} {}", available, this);
        return available;
    }

    @Override
    public boolean hasContent()
    {
        boolean hasContent = _rawContent != null;
        if (LOG.isDebugEnabled())
            LOG.debug("hasContent = {} {}", hasContent, this);
        return hasContent;
    }

    @Override
    public boolean isError()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("isError = {} {}", _error, this);
        return _error;
    }

    @Override
    public void checkMinDataRate()
    {
        long minRequestDataRate = _httpChannel.getHttpConfiguration().getMinRequestDataRate();
        if (LOG.isDebugEnabled())
            LOG.debug("checkMinDataRate [m={},t={}] {}", minRequestDataRate, _firstByteTimeStamp, this);
        if (minRequestDataRate > 0 && _firstByteTimeStamp != Long.MIN_VALUE)
        {
            long period = System.nanoTime() - _firstByteTimeStamp;
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
        if (LOG.isDebugEnabled())
            LOG.debug("getRawContentArrived = {} {}", _rawContentArrived, this);
        return _rawContentArrived;
    }

    @Override
    public boolean consumeAll(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("consumeAll [e={}] {}", x, this);
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
    }

    @Override
    public boolean onContentProducible()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onContentProducible {}", this);
        return _httpChannel.getState().onReadReady();
    }

    @Override
    public HttpInput.Content nextContent()
    {
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

    private HttpInput.Content nextTransformedContent()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("nextTransformedContent {}", this);
        if (_rawContent == null)
        {
            _rawContent = produceRawContent();
            if (_rawContent == null)
                return null;
        }

        if (_transformedContent != null && _transformedContent.isEmpty())
        {
            if (_transformedContent != _rawContent)
                _transformedContent.succeeded();
            if (LOG.isDebugEnabled())
                LOG.debug("nulling depleted transformed content {}", this);
            _transformedContent = null;
        }

        while (_transformedContent == null)
        {
            if (_rawContent.isSpecial())
            {
                // TODO does EOF need to be passed to the interceptors?

                _error = _rawContent.getError() != null;
                if (LOG.isDebugEnabled())
                    LOG.debug("raw content is special (with error = {}), returning it {}", _error, this);
                return _rawContent;
            }

            if (_interceptor != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("using interceptor to transform raw content {}", this);
                _transformedContent = _interceptor.readFrom(_rawContent);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("null interceptor, transformed content = raw content {}", this);
                _transformedContent = _rawContent;
            }

            if (_transformedContent != null && _transformedContent.isEmpty())
            {
                if (_transformedContent != _rawContent)
                    _transformedContent.succeeded();
                if (LOG.isDebugEnabled())
                    LOG.debug("nulling depleted transformed content {}", this);
                _transformedContent = null;
            }

            if (_transformedContent == null)
            {
                if (_rawContent.isEmpty())
                {
                    _rawContent.succeeded();
                    _rawContent = null;
                    if (LOG.isDebugEnabled())
                        LOG.debug("nulling depleted raw content {}", this);
                    _rawContent = produceRawContent();
                    if (_rawContent == null)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("produced null raw content, returning null, {}", this);
                        return null;
                    }
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("raw content is not empty {}", this);
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("transformed content is not empty {}", this);
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("returning transformed content {}", this);
        return _transformedContent;
    }

    private HttpInput.Content produceRawContent()
    {
        HttpInput.Content content = _httpChannel.produceContent();
        if (content != null)
        {
            _rawContentArrived += content.remaining();
            if (_firstByteTimeStamp == Long.MIN_VALUE)
                _firstByteTimeStamp = System.nanoTime();
            if (LOG.isDebugEnabled())
                LOG.debug("produceRawContent updated rawContentArrived to {} and firstByteTimeStamp to {} {}", _rawContentArrived, _firstByteTimeStamp, this);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("produceRawContent produced {} {}", content, this);
        return content;
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
}
