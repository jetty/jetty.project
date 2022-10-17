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
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class CachingContentFactory implements HttpContent.Factory
{
    private static final Logger LOG = LoggerFactory.getLogger(CachingContentFactory.class);

    private final HttpContent.Factory _authority;
    private final ConcurrentMap<String, CachingHttpContent> _cache = new ConcurrentHashMap<>();
    private final AtomicLong _cachedSize = new AtomicLong();
    private int _maxCachedFileSize = 128 * 1024 * 1024;
    private int _maxCachedFiles = 2048;
    private int _maxCacheSize = 256 * 1024 * 1024;

    public CachingContentFactory(HttpContent.Factory authority)
    {
        _authority = authority;
    }

    protected ConcurrentMap<String, CachingHttpContent> getCache()
    {
        return _cache;
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
                long delta = NanoTime.elapsed(c2.getLastAccessed().get(), c1.getLastAccessed().get());
                if (delta != 0)
                    return delta < 0 ? -1 : 1;

                delta = c1.getContentLengthValue() - c2.getContentLengthValue();
                if (delta != 0)
                    return delta < 0 ? -1 : 1;

                return c1.getKey().compareTo(c2.getKey());
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

    protected void removeFromCache(CachingHttpContent content)
    {
        CachingHttpContent removed = _cache.remove(content.getKey());
        if (removed != null)
        {
            removed.release();
            _cachedSize.addAndGet(-removed.getContentLengthValue());
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
            return true;

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
        return (len <= _maxCachedFileSize && len <= _maxCacheSize);
    }

    protected boolean isValid(CachingHttpContent content)
    {
        // Only check the FileSystem once per second, otherwise assume cached value is valid.
        // TODO: should the time between checks be configurable.
        long now = NanoTime.now();
        if (content.getLastAccessed().updateAndGet(lastChecked ->
            (NanoTime.since(lastChecked) > TimeUnit.SECONDS.toNanos(1)) ? now : lastChecked) != now)
            return true;

        if (content instanceof CachedContent cachedContent)
            return Objects.equals(cachedContent.getWrapped().getLastModifiedInstant(), cachedContent.getLastModifiedInstant());

        // TODO:

        if (content instanceof NotFoundContent notFoundContent)
        {
            try
            {
                return _authority.getContent(notFoundContent.getKey()) != null;
            }
            catch (IOException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("", e);
            }
        }

        return true;
    }

    @Override
    public HttpContent getContent(String path) throws IOException
    {
        CachingHttpContent cachingHttpContent = _cache.get(path);
        if (cachingHttpContent != null)
        {
            if (isValid(cachingHttpContent))
                return (cachingHttpContent instanceof NotFoundContent) ? null : cachingHttpContent;
            else
                removeFromCache(cachingHttpContent);
        }

        HttpContent httpContent = _authority.getContent(path);
        if (isCacheable(httpContent))
        {
            AtomicBoolean wasAdded = new AtomicBoolean(false);
            cachingHttpContent = _cache.computeIfAbsent(path, p ->
            {
                wasAdded.set(true);
                CachingHttpContent cachingContent = (httpContent == null) ? new NotFoundContent(p) : new CachedContent(p, httpContent);
                _cachedSize.addAndGet(cachingContent.getContentLengthValue());
                return cachingContent;
            });

            if (wasAdded.get())
                shrinkCache();
            return cachingHttpContent;
        }
        return httpContent;
    }

    protected interface CachingHttpContent extends HttpContent
    {
        AtomicLong getLastAccessed();
        String getKey();
    }

    private static class CachedContent extends HttpContentWrapper implements CachingHttpContent
    {
        private final ByteBuffer _buffer;
        private final String _cacheKey;
        private final HttpField _etagField;
        private final long _contentLengthValue;
        private final AtomicLong _lastAccessed = new AtomicLong();
        private final Set<CompressedContentFormat> _compressedFormats;
        private final String _lastModifiedValue;
        private final String _characterEncoding;
        private final MimeTypes.Type _mimeType;
        private final HttpField _contentLength;
        private final Instant _lastModifiedInstant;
        private final HttpField _lastModified;

        private CachedContent(String key, HttpContent httpContent)
        {
            super(httpContent);
            _cacheKey = key;

            // TODO: do all the following lazily and asynchronously.
            HttpField etagField = httpContent.getETag();
            String eTagValue = httpContent.getETagValue();
            if (StringUtil.isNotBlank(eTagValue))
            {
                etagField = new PreEncodedHttpField(HttpHeader.ETAG, eTagValue);
            }
            _etagField = etagField;

            // Map the content into memory if possible.
            _buffer = httpContent.getBuffer();
            _contentLengthValue = httpContent.getContentLengthValue();
            _lastModifiedValue = httpContent.getLastModifiedValue();
            _characterEncoding = httpContent.getCharacterEncoding();
            _compressedFormats = httpContent.getPreCompressedContentFormats();
            _mimeType = httpContent.getMimeType();
            _contentLength = httpContent.getContentLength();
            _lastModifiedInstant = httpContent.getLastModifiedInstant();
            _lastModified = httpContent.getLastModified();
            _lastAccessed.set(NanoTime.now());
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

        @Override
        public AtomicLong getLastAccessed()
        {
            return _lastAccessed;
        }

        @Override
        public String getKey()
        {
            return _cacheKey;
        }

        @Override
        public void release()
        {
            // TODO re-pool buffer and release precompressed contents
        }

        @Override
        public Set<CompressedContentFormat> getPreCompressedContentFormats()
        {
            return _compressedFormats;
        }

        @Override
        public HttpField getETag()
        {
            return _etagField;
        }

        @Override
        public String getETagValue()
        {
            return _etagField == null ? null : _etagField.getValue();
        }

        @Override
        public String getCharacterEncoding()
        {
            return _characterEncoding;
        }

        @Override
        public MimeTypes.Type getMimeType()
        {
            return _mimeType;
        }

        @Override
        public HttpField getContentLength()
        {
            return _contentLength;
        }

        @Override
        public Instant getLastModifiedInstant()
        {
            return _lastModifiedInstant;
        }

        @Override
        public HttpField getLastModified()
        {
            return _lastModified;
        }

        @Override
        public String getLastModifiedValue()
        {
            return _lastModifiedValue;
        }
    }

    private static class NotFoundContent implements CachingHttpContent
    {
        private final AtomicLong _lastAccessed = new AtomicLong(NanoTime.now());

        private final String _key;

        public NotFoundContent(String key)
        {
            _key = key;
        }

        @Override
        public String getKey()
        {
            return _key;
        }

        @Override
        public AtomicLong getLastAccessed()
        {
            return _lastAccessed;
        }

        @Override
        public HttpField getContentType()
        {
            return null;
        }

        @Override
        public String getContentTypeValue()
        {
            return null;
        }

        @Override
        public String getCharacterEncoding()
        {
            return null;
        }

        @Override
        public MimeTypes.Type getMimeType()
        {
            return null;
        }

        @Override
        public HttpField getContentEncoding()
        {
            return null;
        }

        @Override
        public String getContentEncodingValue()
        {
            return null;
        }

        @Override
        public HttpField getContentLength()
        {
            return null;
        }

        @Override
        public long getContentLengthValue()
        {
            return 0;
        }

        @Override
        public Instant getLastModifiedInstant()
        {
            return null;
        }

        @Override
        public HttpField getLastModified()
        {
            return null;
        }

        @Override
        public String getLastModifiedValue()
        {
            return null;
        }

        @Override
        public HttpField getETag()
        {
            return null;
        }

        @Override
        public String getETagValue()
        {
            return null;
        }

        @Override
        public Resource getResource()
        {
            return null;
        }

        @Override
        public ByteBuffer getBuffer()
        {
            return null;
        }

        @Override
        public Set<CompressedContentFormat> getPreCompressedContentFormats()
        {
            return null;
        }

        @Override
        public void release()
        {
        }
    }
}
