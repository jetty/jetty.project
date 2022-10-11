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

package org.eclipse.jetty.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StringUtil;

/**
 * HttpContent.ContentFactory implementation that wraps any other HttpContent.ContentFactory instance
 * using it as a caching authority.
 * Only HttpContent instances whose path is not a directory are cached.
 * HttpContent instances returned by getContent() implement HttpContent.InMemory when a cached instance is found.
 *
 * TODO should directories (generated HTML) and not-found contents be cached?
 * TODO this form of caching is done at a layer below the request processor (i.e.: done in the guts of the ResourceHandler)
 *  Consider if caching should rather be done at a layer above using a CachingHandler that would intercept Response.write()
 *  of a configured URI set, save that in a cache and serve that again.
 */
public class CachingContentFactory implements HttpContent.ContentFactory
{
    private final HttpContent.ContentFactory _authority;
    private final ConcurrentMap<String, CachingHttpContent> _cache = new ConcurrentHashMap<>();
    private final AtomicLong _cachedSize = new AtomicLong();
    private int _maxCachedFileSize = 128 * 1024 * 1024;
    private int _maxCachedFiles = 2048;
    private int _maxCacheSize = 256 * 1024 * 1024;

    public CachingContentFactory(HttpContent.ContentFactory authority)
    {
        _authority = authority;
    }

    public long getCachedSize()
    {
        return _cachedSize.get();
    }

    public int getCachedFiles()
    {
        return _cache.size();
    }

    public int getMaxCachedFileSize()
    {
        return _maxCachedFileSize;
    }

    public void setMaxCachedFileSize(int maxCachedFileSize)
    {
        _maxCachedFileSize = maxCachedFileSize;
        shrinkCache();
    }

    public int getMaxCacheSize()
    {
        return _maxCacheSize;
    }

    public void setMaxCacheSize(int maxCacheSize)
    {
        _maxCacheSize = maxCacheSize;
        shrinkCache();
    }

    /**
     * @return the max number of cached files.
     */
    public int getMaxCachedFiles()
    {
        return _maxCachedFiles;
    }

    /**
     * @param maxCachedFiles the max number of cached files.
     */
    public void setMaxCachedFiles(int maxCachedFiles)
    {
        _maxCachedFiles = maxCachedFiles;
        shrinkCache();
    }

    private void shrinkCache()
    {
        // While we need to shrink
        while (_cache.size() > 0 && (_cache.size() > _maxCachedFiles || _cachedSize.get() > _maxCacheSize))
        {
            // Scan the entire cache and generate an ordered list by last accessed time.
            SortedSet<CachingHttpContent> sorted = new TreeSet<>((c1, c2) ->
            {
                long delta = NanoTime.elapsed(c2._lastAccessed.get(), c1._lastAccessed.get());
                if (delta != 0)
                    return delta < 0 ? -1 : 1;

                delta = c1._contentLengthValue - c2._contentLengthValue;
                if (delta != 0)
                    return delta < 0 ? -1 : 1;

                return c1._cacheKey.compareTo(c2._cacheKey);
            });
            sorted.addAll(_cache.values());

            // Invalidate least recently used first
            for (CachingHttpContent content : sorted)
            {
                if (_cache.size() <= _maxCachedFiles && _cachedSize.get() <= _maxCacheSize)
                    break;
                removeFromCache(content);
            }
        }
    }

    private void removeFromCache(CachingHttpContent content)
    {
        if (content == _cache.remove(content._cacheKey))
        {
            content.release();
            _cachedSize.addAndGet(-content.getContentLengthValue());
        }
    }

    public void flushCache()
    {
        for (CachingHttpContent content : _cache.values())
        {
            removeFromCache(content);
        }
    }

    /**
     * Tests whether the given HttpContent is cacheable, and if there is enough room to fit it in the cache.
     *
     * @param httpContent the HttpContent to test.
     * @return whether the HttpContent is cacheable.
     */
    protected boolean isCacheable(HttpContent httpContent)
    {
        if (httpContent == null)
            return false;

        if (httpContent.getResource().isDirectory())
            return false;

        if (_maxCachedFiles <= 0)
            return false;

        // Will it fit in the cache?
        long len = httpContent.getContentLengthValue();
        if (len <= 0)
            return false;
        if (httpContent instanceof MappedFileContentFactory.FileMappedContent)
             return true;
        return ((len <= _maxCachedFileSize) && (len + getCachedSize() <= _maxCacheSize));
    }

    @Override
    public HttpContent getContent(String path) throws IOException
    {
        CachingHttpContent cachingHttpContent = _cache.get(path);
        if (cachingHttpContent != null)
        {
            if (cachingHttpContent.isValid())
                return cachingHttpContent;
            else
                removeFromCache(cachingHttpContent);
        }

        HttpContent httpContent = _authority.getContent(path);
        if (isCacheable(httpContent))
        {
            cachingHttpContent = new CachingHttpContent(path, httpContent);
            httpContent = _cache.putIfAbsent(path, cachingHttpContent);
            if (httpContent != null)
            {
                cachingHttpContent.release();
            }
            else
            {
                httpContent = cachingHttpContent;
                _cachedSize.addAndGet(cachingHttpContent.getContentLengthValue());
                shrinkCache();
            }
        }
        return httpContent;
    }

    private static class CachingHttpContent extends HttpContentWrapper
    {
        private final ByteBuffer _buffer;
        private final Instant _lastModifiedValue;
        private final String _cacheKey;
        private final HttpField _etagField;
        private final long _contentLengthValue;
        private final Set<CompressedContentFormat> _precompressedContents;
        private final AtomicLong _lastAccessed = new AtomicLong();

        private CachingHttpContent(String key, HttpContent httpContent) throws IOException
        {
            this(key, httpContent, httpContent.getETagValue());
        }

        private CachingHttpContent(String key, HttpContent httpContent, String etagValue) throws IOException
        {
            super(httpContent);

            if (_delegate.getResource() == null)
                throw new IllegalArgumentException("Null Resource");
            if (!_delegate.getResource().exists())
                throw new IllegalArgumentException("Resource doesn't exist: " + _delegate.getResource());
            if (_delegate.getResource().isDirectory())
                throw new IllegalArgumentException("Directory Resources not supported: " + _delegate.getResource());
            if (_delegate.getResource().getPath() == null) // only required because we need the Path to access the mapped ByteBuffer or SeekableByteChannel.
                throw new IllegalArgumentException("Resource not backed by Path not supported: " + _delegate.getResource());

            // Resources with negative length cannot be cached.
            // But allow resources with zero length.
            long resourceSize = _delegate.getResource().length();
            if (resourceSize < 0)
                throw new IllegalArgumentException("Resource with negative size: " + _delegate.getResource());

            HttpField etagField = _delegate.getETag();
            if (StringUtil.isNotBlank(etagValue))
            {
                etagField = new PreEncodedHttpField(HttpHeader.ETAG, etagValue);
            }
            _etagField = etagField;
            _contentLengthValue = resourceSize;

            // Map the content into memory if possible.
            _buffer = httpContent.getBuffer();
            _cacheKey = key;
            _lastModifiedValue = _delegate.getResource().lastModified();
            _lastAccessed.set(NanoTime.now());
            _precompressedContents = _delegate.getPreCompressedContentFormats();
        }

        @Override
        public long getContentLengthValue()
        {
            return _contentLengthValue;
        }

        @Override
        public ByteBuffer getBuffer()
        {
            // TODO this should return a RetainableByteBuffer otherwise there is a race between
            //  threads serving the buffer while another thread invalidates it. That's going to
            //  be a lot of fun since RetainableByteBuffer is only meant to be acquired from a pool
            //  but the byte buffer here could be coming from a mmap'ed file.
            return _buffer.slice();
        }

        public boolean isValid()
        {
            // Only check the FileSystem once per second, otherwise assume cached value is valid.
            // TODO: should the time between checks be configurable.
            long now = NanoTime.now();
            if (_lastAccessed.updateAndGet(lastChecked ->
                (NanoTime.since(lastChecked) > TimeUnit.SECONDS.toNanos(1)) ? now : lastChecked) != now)
                return true;

            Instant lastModifiedTime = _delegate.getResource().lastModified();
            if (lastModifiedTime.equals(_lastModifiedValue))
                return true;
            release();
            return false;
        }

        @Override
        public void release()
        {
            // TODO re-pool buffer and release precompressed contents
        }

        @Override
        public HttpField getETag()
        {
            return _etagField;
        }

        @Override
        public String getETagValue()
        {
            HttpField etag = getETag();
            if (etag == null)
                return null;
            return etag.getValue();
        }

        @Override
        public Set<CompressedContentFormat> getPreCompressedContentFormats()
        {
            return _precompressedContents;
        }
    }
}
